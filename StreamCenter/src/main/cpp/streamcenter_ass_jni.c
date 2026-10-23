#include <jni.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>

#include <android/bitmap.h>
#include <android/log.h>

#include "ass.h"

#define LOG_TAG "StreamCenterAss"
#define JNI_PREFIX(name) Java_it_dogior_hadEnough_subtitles_LibassNative_##name

static jclass frame_class;
static jmethodID frame_init;
static jclass image_class;
static jmethodID image_init;
static jclass bitmap_class;
static jmethodID bitmap_create;
static jobject alpha8_config;

static jclass global_class(JNIEnv *env, const char *name)
{
    jclass local = (*env)->FindClass(env, name);
    if (!local)
        return NULL;
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    return global;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;

    frame_class = global_class(env, "it/dogior/hadEnough/subtitles/AssFrame");
    image_class = global_class(env, "it/dogior/hadEnough/subtitles/AssImage");
    bitmap_class = global_class(env, "android/graphics/Bitmap");
    jclass config_class = (*env)->FindClass(env, "android/graphics/Bitmap$Config");
    if (!frame_class || !image_class || !bitmap_class || !config_class)
        return JNI_ERR;

    frame_init = (*env)->GetMethodID(env, frame_class, "<init>",
                                     "([Lit/dogior/hadEnough/subtitles/AssImage;Z)V");
    image_init = (*env)->GetMethodID(env, image_class, "<init>", "(IIILandroid/graphics/Bitmap;)V");
    bitmap_create = (*env)->GetStaticMethodID(env, bitmap_class, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jfieldID alpha8 = (*env)->GetStaticFieldID(env, config_class, "ALPHA_8",
                                               "Landroid/graphics/Bitmap$Config;");
    if (!frame_init || !image_init || !bitmap_create || !alpha8)
        return JNI_ERR;

    jobject config = (*env)->GetStaticObjectField(env, config_class, alpha8);
    alpha8_config = (*env)->NewGlobalRef(env, config);
    (*env)->DeleteLocalRef(env, config);
    (*env)->DeleteLocalRef(env, config_class);
    return JNI_VERSION_1_6;
}

static void log_message(int level, const char *format, va_list args, void *data)
{
    if (level > 1)
        return;
    __android_log_vprint(ANDROID_LOG_ERROR, LOG_TAG, format, args);
}

JNIEXPORT jlong JNICALL JNI_PREFIX(libraryInit)(JNIEnv *env, jclass clazz)
{
    ASS_Library *library = ass_library_init();
    if (!library)
        return 0;
    ass_set_message_cb(library, log_message, NULL);
    ass_set_extract_fonts(library, 1);
    return (jlong) (intptr_t) library;
}

JNIEXPORT void JNICALL JNI_PREFIX(libraryAddFont)(JNIEnv *env, jclass clazz, jlong library,
                                                 jstring name, jbyteArray data)
{
    const char *font_name = (*env)->GetStringUTFChars(env, name, NULL);
    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (font_name && bytes)
        ass_add_font((ASS_Library *) (intptr_t) library, font_name, (const char *) bytes, size);
    if (bytes)
        (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (font_name)
        (*env)->ReleaseStringUTFChars(env, name, font_name);
}

JNIEXPORT void JNICALL JNI_PREFIX(libraryDone)(JNIEnv *env, jclass clazz, jlong library)
{
    ass_library_done((ASS_Library *) (intptr_t) library);
}

JNIEXPORT jlong JNICALL JNI_PREFIX(trackNew)(JNIEnv *env, jclass clazz, jlong library)
{
    return (jlong) (intptr_t) ass_new_track((ASS_Library *) (intptr_t) library);
}

JNIEXPORT void JNICALL JNI_PREFIX(trackReadHeader)(JNIEnv *env, jclass clazz, jlong track,
                                                  jbyteArray data)
{
    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes)
        return;
    ass_process_codec_private((ASS_Track *) (intptr_t) track, (const char *) bytes, size);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL JNI_PREFIX(trackReadChunk)(JNIEnv *env, jclass clazz, jlong track,
                                                 jbyteArray data, jint offset, jint length,
                                                 jlong start_ms, jlong duration_ms)
{
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes)
        return;
    ass_process_chunk((ASS_Track *) (intptr_t) track, (const char *) bytes + offset, length,
                      start_ms, duration_ms);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL JNI_PREFIX(trackFree)(JNIEnv *env, jclass clazz, jlong track)
{
    ass_free_track((ASS_Track *) (intptr_t) track);
}

JNIEXPORT jlong JNICALL JNI_PREFIX(rendererInit)(JNIEnv *env, jclass clazz, jlong library)
{
    ASS_Renderer *renderer = ass_renderer_init((ASS_Library *) (intptr_t) library);
    if (!renderer)
        return 0;
    ass_set_fonts(renderer, NULL, NULL, ASS_FONTPROVIDER_AUTODETECT, NULL, 1);
    return (jlong) (intptr_t) renderer;
}

JNIEXPORT void JNICALL JNI_PREFIX(rendererSetFrameSize)(JNIEnv *env, jclass clazz, jlong renderer,
                                                       jint width, jint height)
{
    ass_set_frame_size((ASS_Renderer *) (intptr_t) renderer, width, height);
}

JNIEXPORT void JNICALL JNI_PREFIX(rendererSetStorageSize)(JNIEnv *env, jclass clazz,
                                                         jlong renderer, jint width, jint height)
{
    ass_set_storage_size((ASS_Renderer *) (intptr_t) renderer, width, height);
}

static jobject create_bitmap(JNIEnv *env, const ASS_Image *image)
{
    jobject bitmap = (*env)->CallStaticObjectMethod(env, bitmap_class, bitmap_create,
                                                    image->w, image->h, alpha8_config);
    if (!bitmap || (*env)->ExceptionCheck(env))
        return NULL;

    AndroidBitmapInfo info;
    void *pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        (*env)->DeleteLocalRef(env, bitmap);
        return NULL;
    }
    for (int row = 0; row < image->h; row++)
        memcpy((uint8_t *) pixels + (size_t) row * info.stride,
               image->bitmap + (size_t) row * image->stride, image->w);
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

static jobjectArray create_images(JNIEnv *env, const ASS_Image *images)
{
    jsize count = 0;
    for (const ASS_Image *image = images; image; image = image->next) {
        if (image->w > 0 && image->h > 0)
            count++;
    }

    jobjectArray array = (*env)->NewObjectArray(env, count, image_class, NULL);
    if (!array)
        return NULL;

    jsize index = 0;
    for (const ASS_Image *image = images; image && index < count; image = image->next) {
        if (image->w <= 0 || image->h <= 0)
            continue;
        jobject bitmap = create_bitmap(env, image);
        if (!bitmap)
            return NULL;
        jobject item = (*env)->NewObject(env, image_class, image_init, image->dst_x, image->dst_y,
                                         (jint) image->color, bitmap);
        (*env)->DeleteLocalRef(env, bitmap);
        if (!item)
            return NULL;
        (*env)->SetObjectArrayElement(env, array, index++, item);
        (*env)->DeleteLocalRef(env, item);
    }
    return array;
}

JNIEXPORT jobject JNICALL JNI_PREFIX(rendererRender)(JNIEnv *env, jclass clazz, jlong renderer,
                                                    jlong track, jlong time_ms)
{
    int changed = 0;
    ASS_Image *images = ass_render_frame((ASS_Renderer *) (intptr_t) renderer,
                                         (ASS_Track *) (intptr_t) track, time_ms, &changed);
    if (!changed)
        return (*env)->NewObject(env, frame_class, frame_init, NULL, JNI_FALSE);

    jobjectArray array = create_images(env, images);
    if (!array)
        return NULL;
    jobject frame = (*env)->NewObject(env, frame_class, frame_init, array, JNI_TRUE);
    (*env)->DeleteLocalRef(env, array);
    return frame;
}

JNIEXPORT void JNICALL JNI_PREFIX(rendererDone)(JNIEnv *env, jclass clazz, jlong renderer)
{
    ass_renderer_done((ASS_Renderer *) (intptr_t) renderer);
}
