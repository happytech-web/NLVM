# Usage
先跑setup_refs.sh来克隆BUAA仓库

setup_refs.sh是给compare-BUAA脚本用来build的，你也可以手动执行来进行build，脚本里也会自动build

确保你的路径里有这些内容:
- sysy-runtime/armv7/libsysy.a: 由armv7的gcc产生
  - 我本地的命令是这样的:
    - `armv7l-unknown-linux-gnueabihf-gcc -O2 -march=armv7ve -mfpu=vfpv3-d16 -mfloat-abi=hard -c ../sylib.c -o sylib.o`
    - `armv7l-unknown-linux-gnueabihf-ar rcs libsysy.a sylib.o`
- armv7l-unknown-linux-gnueabihf-gcc: BUAA是armv7的
- qemu-arm: 理由同上
- 同时确保你有正常跑aarch64的环境
