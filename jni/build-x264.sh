set -ex

./configure \
    --cross-prefix=arm-linux-androideabi- \
    --enable-pic \
    --host=arm-linux

make -j4
