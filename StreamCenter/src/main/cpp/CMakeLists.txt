cmake_minimum_required(VERSION 3.22)
project(streamcenter_ass C CXX ASM)

include(FetchContent)

set(FETCHCONTENT_BASE_DIR "${STREAMCENTER_DEPS_DIR}" CACHE PATH "" FORCE)
set(FETCHCONTENT_UPDATES_DISCONNECTED ON)

function(fetch_source name url sha256)
    FetchContent_Declare(${name}
        URL ${url}
        URL_HASH SHA256=${sha256}
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
        SOURCE_SUBDIR streamcenter-no-cmake
    )
    FetchContent_MakeAvailable(${name})
    set(${name}_SOURCE_DIR "${${name}_SOURCE_DIR}" PARENT_SCOPE)
endfunction()

file(MAKE_DIRECTORY "${STREAMCENTER_DEPS_DIR}")
file(LOCK "${STREAMCENTER_DEPS_DIR}/.lock" TIMEOUT 900)

fetch_source(freetype
    https://download.savannah.gnu.org/releases/freetype/freetype-2.14.3.tar.xz
    36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f)
fetch_source(fribidi
    https://github.com/fribidi/fribidi/releases/download/v1.0.17/fribidi-1.0.17.tar.xz
    6949dcde27d41cebad1fd741fcafc36d55a1020d2d872d4a6eb3914caabbada2)
fetch_source(harfbuzz
    https://github.com/harfbuzz/harfbuzz/releases/download/14.6.0/harfbuzz-14.6.0.tar.xz
    d07a007327277708a2a73ae437887cdbaf282937f6d03ca5467723e9099af586)
fetch_source(libass
    https://github.com/libass/libass/releases/download/0.17.5/libass-0.17.5.tar.xz
    2dca25c0e0c837ddf00b52011b3f82cac1e4ddd3ad018227806b0c2288864acc)

file(LOCK "${STREAMCENTER_DEPS_DIR}/.lock" RELEASE)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
add_compile_options(-O2 -ffunction-sections -fdata-sections -w)

set(FT_DISABLE_ZLIB ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BZIP2 ON CACHE BOOL "" FORCE)
set(FT_DISABLE_PNG ON CACHE BOOL "" FORCE)
set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BROTLI ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory("${freetype_SOURCE_DIR}" freetype EXCLUDE_FROM_ALL)

file(GLOB FRIBIDI_SOURCES "${fribidi_SOURCE_DIR}/lib/*.c")
add_library(fribidi STATIC ${FRIBIDI_SOURCES})
target_include_directories(fribidi
    PUBLIC "${fribidi_SOURCE_DIR}/lib" "${CMAKE_CURRENT_SOURCE_DIR}/config/fribidi")
target_compile_definitions(fribidi
    PUBLIC FRIBIDI_LIB_STATIC
    PRIVATE HAVE_STDLIB_H=1 HAVE_STRING_H=1 HAVE_STRINGIZE=1 STDC_HEADERS=1)

add_library(harfbuzz STATIC "${harfbuzz_SOURCE_DIR}/src/harfbuzz.cc")
target_include_directories(harfbuzz PUBLIC "${harfbuzz_SOURCE_DIR}/src")
target_compile_definitions(harfbuzz PRIVATE HAVE_PTHREAD HAVE_UNISTD_H HAVE_SYS_MMAN_H HAVE_MMAP)
target_compile_options(harfbuzz PRIVATE -fno-exceptions -fno-rtti -fno-threadsafe-statics)
set_target_properties(harfbuzz PROPERTIES CXX_STANDARD 17)

set(LIBASS_DIR "${libass_SOURCE_DIR}/libass")
set(LIBASS_SOURCES
    c/c_be_blur.c c/c_blend_bitmaps.c c/c_blur.c c/c_rasterizer.c
    ass.c ass_arabic_charmap.c ass_bitmap.c ass_bitmap_engine.c ass_blur.c
    ass_cache.c ass_drawing.c ass_filesystem.c ass_font.c ass_fontselect.c
    ass_library.c ass_outline.c ass_parse.c ass_rasterizer.c ass_render.c
    ass_render_api.c ass_shaper.c ass_string.c ass_strtod.c ass_utils.c)
list(TRANSFORM LIBASS_SOURCES PREPEND "${LIBASS_DIR}/")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    list(APPEND LIBASS_SOURCES
        "${LIBASS_DIR}/aarch64/asm.S" "${LIBASS_DIR}/aarch64/be_blur.S"
        "${LIBASS_DIR}/aarch64/blend_bitmaps.S" "${LIBASS_DIR}/aarch64/blur.S"
        "${LIBASS_DIR}/aarch64/rasterizer.S")
endif()
add_library(ass STATIC ${LIBASS_SOURCES} android_fonts.c)
target_include_directories(ass
    PUBLIC "${LIBASS_DIR}"
    PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/config/libass")
target_link_libraries(ass PUBLIC freetype fribidi harfbuzz)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(ass PRIVATE CONFIG_ASM=1 ARCH_AARCH64=1)
endif()
set_source_files_properties("${LIBASS_DIR}/ass_fontselect.c" PROPERTIES COMPILE_DEFINITIONS
    "CONFIG_FONTCONFIG=1;ass_fontconfig_add_provider=ass_android_add_provider")

add_library(streamcenter_ass SHARED streamcenter_ass_jni.c)
target_link_libraries(streamcenter_ass PRIVATE ass jnigraphics log m)
target_link_options(streamcenter_ass PRIVATE
    -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,--build-id=none)
