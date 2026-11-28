cmake_minimum_required(VERSION 3.22)

# 央视 h5e 点播加密流原生解密（MiTV4A 移植）。
# - generated/cctv_h5e_wasm.c|h、cctv_h5e_memory_init.h：CCTV 官方点播播放器内嵌 wasm
#   的 wasm2c 转译产物（源自 xiaoxi-ij478/cctv-h5e-decrypt，经 qianxuntudou-ops/MyTVB
#   整理为 cpp/cctv_h5e/）。
# - cctv_h5e_decryptor.c：TS 流级解密封装（TS demux + NALU 解密调度 + wasm 运行时桥），
#   JNI 包名已改为 com.nuvio.tv.core.cctv.CctvH5eDecryptor。
# - wasm-rt/：wasm2c 官方运行时。
# 编译参数对齐上游：-O3 -DNDEBUG -DWASM_RT_USE_MMAP=0。
add_library(cctv_h5e SHARED
    cctv_h5e_decryptor.c
    generated/cctv_h5e_wasm.c
    wasm-rt/wasm-rt-impl.c
    wasm-rt/wasm-rt-mem-impl.c)
target_include_directories(cctv_h5e PRIVATE generated wasm-rt)
target_compile_options(cctv_h5e PRIVATE -O3 -DNDEBUG -std=c99 -DWASM_RT_USE_MMAP=0 -fvisibility=hidden)
target_link_libraries(cctv_h5e log m)
