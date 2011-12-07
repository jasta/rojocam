set -ex

x264_path="$HOME/android/x264"

./configure \
    --arch=arm5te \
    --enable-armv5te \
    --target-os=linux \
    --cross-prefix=arm-linux-androideabi- \
    --extra-cflags="-fPIC -DANDROID -D__thumb__ -mthumb -Wfatal-errors -Wno-deprecated" \
    --enable-cross-compile \
    --disable-shared \
    --enable-static \
    --enable-small \
    --disable-ffmpeg \
    --disable-ffplay \
    --disable-ffprobe \
    --disable-ffserver \
    --disable-avfilter \
    --disable-avdevice \
    --enable-libx264 \
    --enable-encoder=libx264 \
    --enable-gpl \
    --disable-neon \
    --enable-memalign-hack \
    --extra-cflags="-I$x264_path" \
    --extra-ldflags="-L$x264_path"

make -j4
