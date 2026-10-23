#include "config.h"

#include <dirent.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_SFNT_NAMES_H
#include FT_TRUETYPE_IDS_H

#include "ass_font.h"
#include "ass_fontselect.h"
#include "ass_utils.h"

#define MAX_NAMES 32
#define FALLBACK_SLOTS 1024
#define FALLBACK_NONE -1

typedef struct {
    char *path;
    int index;
    int rank;
    ASS_FontProviderMetaData meta;
} SystemFont;

typedef struct {
    SystemFont *font;
    FT_Library ftlib;
    FT_Face face;
} FontHandle;

typedef struct {
    uint32_t codepoint;
    int font;
    bool used;
} FallbackSlot;

typedef struct {
    FT_Library ftlib;
    FallbackSlot fallbacks[FALLBACK_SLOTS];
} ProviderData;

static const char *const font_dirs[] = { "/system/fonts", "/product/fonts" };

static pthread_mutex_t catalog_lock = PTHREAD_MUTEX_INITIALIZER;
static SystemFont *catalog;
static size_t catalog_size;
static int catalog_ready;

static bool has_font_extension(const char *name)
{
    const char *dot = strrchr(name, '.');
    return dot && (!strcasecmp(dot, ".ttf") || !strcasecmp(dot, ".otf") ||
                   !strcasecmp(dot, ".ttc") || !strcasecmp(dot, ".otc"));
}

static int font_rank(const char *family)
{
    if (!strcasecmp(family, "Roboto"))
        return 0;
    if (!strncasecmp(family, "Roboto", 6))
        return 1;
    if (!strncasecmp(family, "Noto Sans", 9) && !strstr(family, "Mono"))
        return 2;
    if (!strncasecmp(family, "Noto", 4))
        return 3;
    return 4;
}

static char **collect_names(FT_Face face, FT_UShort name_id, int *count)
{
    char *found[MAX_NAMES];
    int n = 0;
    FT_UInt total = FT_Get_Sfnt_Name_Count(face);

    for (FT_UInt i = 0; i < total && n < MAX_NAMES; i++) {
        FT_SfntName name;
        if (FT_Get_Sfnt_Name(face, i, &name) || name.platform_id != TT_PLATFORM_MICROSOFT ||
            name.name_id != name_id)
            continue;
        char buf[1024];
        ass_utf16be_to_utf8(buf, sizeof(buf), name.string, name.string_len);
        if (buf[0])
            found[n++] = strdup(buf);
    }

    if (!n && name_id == TT_NAME_ID_FONT_FAMILY && face->family_name)
        found[n++] = strdup(face->family_name);

    *count = n;
    if (!n)
        return NULL;
    char **names = malloc(sizeof(char *) * n);
    memcpy(names, found, sizeof(char *) * n);
    return names;
}

static bool read_meta(FT_Face face, ASS_FontProviderMetaData *meta)
{
    if (!(face->face_flags & FT_FACE_FLAG_SCALABLE))
        return false;

    meta->families = collect_names(face, TT_NAME_ID_FONT_FAMILY, &meta->n_family);
    if (!meta->n_family)
        return false;
    meta->fullnames = collect_names(face, TT_NAME_ID_FULL_NAME, &meta->n_fullname);

    const char *postscript = FT_Get_Postscript_Name(face);
    meta->postscript_name = postscript ? strdup(postscript) : NULL;
    meta->extended_family = strdup(meta->families[0]);
    meta->weight = ass_face_get_weight(face);
    meta->style_flags = ass_face_get_style_flags(face);
    meta->is_postscript = ass_face_is_postscript(face);
    return true;
}

static void catalog_add(SystemFont font)
{
    catalog = realloc(catalog, sizeof(SystemFont) * (catalog_size + 1));
    catalog[catalog_size++] = font;
}

static void scan_file(FT_Library ftlib, const char *path)
{
    FT_Face face;
    if (FT_New_Face(ftlib, path, -1, &face))
        return;
    FT_Long faces = face->num_faces;
    FT_Done_Face(face);

    for (FT_Long index = 0; index < faces; index++) {
        if (FT_New_Face(ftlib, path, index, &face))
            continue;
        SystemFont font = { .index = (int) index };
        if (read_meta(face, &font.meta)) {
            font.path = strdup(path);
            font.rank = font_rank(font.meta.families[0]);
            catalog_add(font);
        }
        FT_Done_Face(face);
    }
}

static int compare_rank(const void *a, const void *b)
{
    return ((const SystemFont *) a)->rank - ((const SystemFont *) b)->rank;
}

static void ensure_catalog(FT_Library ftlib)
{
    pthread_mutex_lock(&catalog_lock);
    if (!catalog_ready) {
        for (size_t d = 0; d < sizeof(font_dirs) / sizeof(font_dirs[0]); d++) {
            DIR *dir = opendir(font_dirs[d]);
            if (!dir)
                continue;
            struct dirent *entry;
            while ((entry = readdir(dir))) {
                if (entry->d_name[0] == '.' || !has_font_extension(entry->d_name))
                    continue;
                char path[512];
                snprintf(path, sizeof(path), "%s/%s", font_dirs[d], entry->d_name);
                scan_file(ftlib, path);
            }
            closedir(dir);
        }
        if (catalog_size)
            qsort(catalog, catalog_size, sizeof(SystemFont), compare_rank);
        catalog_ready = 1;
    }
    pthread_mutex_unlock(&catalog_lock);
}

static bool face_has_glyph(FT_Library ftlib, const SystemFont *font, uint32_t codepoint)
{
    FT_Face face;
    if (FT_New_Face(ftlib, font->path, font->index, &face))
        return false;
    bool found = FT_Get_Char_Index(face, codepoint) != 0;
    FT_Done_Face(face);
    return found;
}

static bool check_glyph(void *data, uint32_t codepoint)
{
    FontHandle *handle = data;
    if (!codepoint)
        return true;
    if (!handle->face &&
        FT_New_Face(handle->ftlib, handle->font->path, handle->font->index, &handle->face))
        return false;
    return FT_Get_Char_Index(handle->face, codepoint) != 0;
}

static void destroy_font(void *data)
{
    FontHandle *handle = data;
    if (handle->face)
        FT_Done_Face(handle->face);
    free(handle);
}

static void destroy_provider(void *data)
{
    free(data);
}

static int find_fallback(FT_Library ftlib, uint32_t codepoint)
{
    for (size_t i = 0; i < catalog_size; i++) {
        if (!codepoint || face_has_glyph(ftlib, &catalog[i], codepoint))
            return (int) i;
    }
    return FALLBACK_NONE;
}

static char *get_fallback(void *data, ASS_Library *lib, const char *family, uint32_t codepoint)
{
    ProviderData *provider = data;
    FallbackSlot *slot = &provider->fallbacks[codepoint % FALLBACK_SLOTS];
    if (!slot->used || slot->codepoint != codepoint) {
        slot->codepoint = codepoint;
        slot->font = find_fallback(provider->ftlib, codepoint);
        slot->used = true;
    }
    return slot->font == FALLBACK_NONE ? NULL : strdup(catalog[slot->font].meta.extended_family);
}

static ASS_FontProviderFuncs android_funcs = {
    .check_glyph = check_glyph,
    .destroy_font = destroy_font,
    .destroy_provider = destroy_provider,
    .get_fallback = get_fallback,
};

ASS_FontProvider *ass_android_add_provider(ASS_Library *lib, ASS_FontSelector *selector,
                                           const char *config, FT_Library ftlib)
{
    ensure_catalog(ftlib);
    if (!catalog_size)
        return NULL;

    ProviderData *data = calloc(1, sizeof(ProviderData));
    if (!data)
        return NULL;
    data->ftlib = ftlib;

    ASS_FontProvider *provider = ass_font_provider_new(selector, &android_funcs, data);
    if (!provider) {
        free(data);
        return NULL;
    }

    for (size_t i = 0; i < catalog_size; i++) {
        FontHandle *handle = calloc(1, sizeof(FontHandle));
        if (!handle)
            break;
        handle->font = &catalog[i];
        handle->ftlib = ftlib;
        ass_font_provider_add_font(provider, &catalog[i].meta, catalog[i].path,
                                   catalog[i].index, handle);
    }
    return provider;
}
