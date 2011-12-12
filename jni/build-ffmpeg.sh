set -ex

x264_path="$HOME/android/x264"

./configure \
    --disable-everything \
    --arch=arm5te \
    --disable-neon \
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
    --enable-network \
    --enable-protocol=tcp \
    --enable-protocol=udp \
    --enable-protocol=rtp \
    --enable-encoder=mpeg4 \
    --enable-muxer=rtsp \
    --enable-muxer=rtp \
    --enable-libx264 \
    --enable-encoder=libx264 \
    --enable-gpl \
    --enable-memalign-hack \
    --extra-cflags="-I$x264_path" \
    --extra-ldflags="-L$x264_path"

make -j4
