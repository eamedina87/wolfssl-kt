# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.4.1)

# Add preprocessor defines to CFLAGS
add_definitions(
        -DHAVE_FFDHE_2048
        -DWOLFSSL_TLS13
        -DHAVE_TLS_EXTENSIONS
        -DHAVE_HKDF
        -DHAVE_ECC
        -DWC_RSA_PSS
        -DHAVE_POLY1305
        -DHAVE_CHACHA
        -DWOLFSSL_JNI
        -DOPENSSL_ALL
        -DOPENSSL_EXTRA
        -DWOLFSSL_CERT_GEN
        -DWOLFSSL_CERT_REQ
        -DNO_DSA
        -DWOLFSSL_HAVE_SP_RSA
        -DHAVE_CRL
        -DHAVE_SUPPORTED_CURVES
        -DHAVE_EX_DATA
        -DHAVE_PK_CALLBACKS
)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_definitions(
        -DDEBUG_WOLFSSL
        -DWOLFSSL_DEBUG_TLS
    )
endif()

# set wolfSSL JNI location as environment variable, change if needed
set(wolfssljni_DIR ${CMAKE_CURRENT_LIST_DIR}/wolfssl-jni-jsse-1.16.0)
set(wolfssl_DIR    ${CMAKE_CURRENT_LIST_DIR}/wolfssl-5.8.4)


# Add header directories to include paths
include_directories(
    ${wolfssl_DIR}
    ${wolfssljni_DIR}/native
)

# Add wolfSSL library source files, to be compiled as SHARED library
add_library(wolfssl SHARED
        ${wolfssl_DIR}/wolfcrypt/src/aes.c
        ${wolfssl_DIR}/wolfcrypt/src/arc4.c
        ${wolfssl_DIR}/wolfcrypt/src/asm.c
        ${wolfssl_DIR}/wolfcrypt/src/asn.c
        ${wolfssl_DIR}/wolfcrypt/src/blake2b.c
        ${wolfssl_DIR}/wolfcrypt/src/blake2s.c
        ${wolfssl_DIR}/wolfcrypt/src/camellia.c
        ${wolfssl_DIR}/wolfcrypt/src/chacha.c
        ${wolfssl_DIR}/wolfcrypt/src/chacha20_poly1305.c
        ${wolfssl_DIR}/wolfcrypt/src/cmac.c
        ${wolfssl_DIR}/wolfcrypt/src/coding.c
        ${wolfssl_DIR}/wolfcrypt/src/compress.c
        ${wolfssl_DIR}/wolfcrypt/src/cpuid.c
        ${wolfssl_DIR}/wolfcrypt/src/cryptocb.c
        ${wolfssl_DIR}/wolfcrypt/src/curve25519.c
        ${wolfssl_DIR}/wolfcrypt/src/curve448.c
        ${wolfssl_DIR}/wolfcrypt/src/des3.c
        ${wolfssl_DIR}/wolfcrypt/src/dh.c
        ${wolfssl_DIR}/wolfcrypt/src/dsa.c
        ${wolfssl_DIR}/wolfcrypt/src/ecc.c
        ${wolfssl_DIR}/wolfcrypt/src/ecc_fp.c
        ${wolfssl_DIR}/wolfcrypt/src/eccsi.c
        ${wolfssl_DIR}/wolfcrypt/src/ed25519.c
        ${wolfssl_DIR}/wolfcrypt/src/ed448.c
        ${wolfssl_DIR}/wolfcrypt/src/error.c
        ${wolfssl_DIR}/wolfcrypt/src/fe_448.c
        ${wolfssl_DIR}/wolfcrypt/src/fe_low_mem.c
        ${wolfssl_DIR}/wolfcrypt/src/fe_operations.c
        ${wolfssl_DIR}/wolfcrypt/src/ge_448.c
        ${wolfssl_DIR}/wolfcrypt/src/ge_low_mem.c
        ${wolfssl_DIR}/wolfcrypt/src/ge_operations.c
        ${wolfssl_DIR}/wolfcrypt/src/hash.c
        ${wolfssl_DIR}/wolfcrypt/src/hmac.c
        ${wolfssl_DIR}/wolfcrypt/src/integer.c
        ${wolfssl_DIR}/wolfcrypt/src/kdf.c
        ${wolfssl_DIR}/wolfcrypt/src/logging.c
        ${wolfssl_DIR}/wolfcrypt/src/md2.c
        ${wolfssl_DIR}/wolfcrypt/src/md4.c
        ${wolfssl_DIR}/wolfcrypt/src/md5.c
        ${wolfssl_DIR}/wolfcrypt/src/memory.c
        ${wolfssl_DIR}/wolfcrypt/src/pkcs12.c
        ${wolfssl_DIR}/wolfcrypt/src/pkcs7.c
        ${wolfssl_DIR}/wolfcrypt/src/poly1305.c
        ${wolfssl_DIR}/wolfcrypt/src/pwdbased.c
        ${wolfssl_DIR}/wolfcrypt/src/random.c
        ${wolfssl_DIR}/wolfcrypt/src/rc2.c
        ${wolfssl_DIR}/wolfcrypt/src/ripemd.c
        ${wolfssl_DIR}/wolfcrypt/src/rsa.c
        ${wolfssl_DIR}/wolfcrypt/src/sakke.c
        ${wolfssl_DIR}/wolfcrypt/src/sha256.c
        ${wolfssl_DIR}/wolfcrypt/src/sha3.c
        ${wolfssl_DIR}/wolfcrypt/src/sha512.c
        ${wolfssl_DIR}/wolfcrypt/src/sha.c
        ${wolfssl_DIR}/wolfcrypt/src/signature.c
        ${wolfssl_DIR}/wolfcrypt/src/sp_arm32.c
        ${wolfssl_DIR}/wolfcrypt/src/sp_arm64.c
        ${wolfssl_DIR}/wolfcrypt/src/sp_armthumb.c
        ${wolfssl_DIR}/wolfcrypt/src/sp_c32.c
        ${wolfssl_DIR}/wolfcrypt/src/sp_c64.c
        ${wolfssl_DIR}/wolfcrypt/src/sp_cortexm.c
        ${wolfssl_DIR}/wolfcrypt/src/sp_dsp32.c
        ${wolfssl_DIR}/wolfcrypt/src/sp_int.c
        ${wolfssl_DIR}/wolfcrypt/src/sp_x86_64.c
        ${wolfssl_DIR}/wolfcrypt/src/srp.c
        ${wolfssl_DIR}/wolfcrypt/src/tfm.c
        ${wolfssl_DIR}/wolfcrypt/src/wc_dsp.c
        ${wolfssl_DIR}/wolfcrypt/src/wc_encrypt.c
        ${wolfssl_DIR}/wolfcrypt/src/wc_pkcs11.c
        ${wolfssl_DIR}/wolfcrypt/src/wc_port.c
        ${wolfssl_DIR}/wolfcrypt/src/wolfevent.c
        ${wolfssl_DIR}/wolfcrypt/src/wolfmath.c
        ${wolfssl_DIR}/src/crl.c
        ${wolfssl_DIR}/src/dtls13.c
        ${wolfssl_DIR}/src/dtls.c
        ${wolfssl_DIR}/src/internal.c
        ${wolfssl_DIR}/src/keys.c
        ${wolfssl_DIR}/src/ocsp.c
        ${wolfssl_DIR}/src/pk.c
        ${wolfssl_DIR}/src/sniffer.c
        ${wolfssl_DIR}/src/ssl.c
        ${wolfssl_DIR}/src/tls13.c
        ${wolfssl_DIR}/src/tls.c
        ${wolfssl_DIR}/src/wolfio.c
        )

# Add wolfSSL JNI library native source files, to be compiled as SHARED library
# wolfSSL JNI Java files are tied into build in Module build.gradle file
add_library(wolfssljni SHARED
        ${wolfssljni_DIR}/native/com_wolfssl_WolfCryptECC.c
        ${wolfssljni_DIR}/native/com_wolfssl_WolfCryptEccKey.c
        ${wolfssljni_DIR}/native/com_wolfssl_WolfCryptRSA.c
        ${wolfssljni_DIR}/native/com_wolfssl_WolfSSL.c
        ${wolfssljni_DIR}/native/com_wolfssl_WolfSSLCertificate.c
        ${wolfssljni_DIR}/native/com_wolfssl_WolfSSLCertManager.c
        ${wolfssljni_DIR}/native/com_wolfssl_WolfSSLCertRequest.c
        ${wolfssljni_DIR}/native/com_wolfssl_WolfSSLContext.c
        ${wolfssljni_DIR}/native/com_wolfssl_WolfSSLSession.c
        ${wolfssljni_DIR}/native/com_wolfssl_WolfSSLX509Name.c
        ${wolfssljni_DIR}/native/com_wolfssl_WolfSSLX509StoreCtx.c
        )

# Include libraries needed for wolfSSL and wolfSSL JNI libs
target_link_libraries(
        wolfssljni
        wolfssl
        android
        log
)

# Align segments for 16 KB page size devices (Android 15+ requirement).
target_link_options(wolfssl PRIVATE -Wl,-z,max-page-size=16384)
target_link_options(wolfssljni PRIVATE -Wl,-z,max-page-size=16384)
