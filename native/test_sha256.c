#include <stdio.h>
#include <string.h>

#include "sha256.h"

static void hex(const uint8_t digest[SHA256_DIGEST_SIZE], char out[65]) {
    static const char *H = "0123456789abcdef";
    for (int i = 0; i < SHA256_DIGEST_SIZE; i++) {
        out[i * 2] = H[digest[i] >> 4];
        out[i * 2 + 1] = H[digest[i] & 15];
    }
    out[64] = 0;
}

static int check(const char *input, const char *expected) {
    uint8_t digest[SHA256_DIGEST_SIZE];
    char out[65];
    sha256_buffer((const uint8_t *) input, strlen(input), digest);
    hex(digest, out);
    if (strcmp(out, expected) != 0) {
        fprintf(stderr, "FAIL '%s'\n  expected %s\n  actual   %s\n", input, expected, out);
        return 1;
    }
    printf("ok  %s -> %s\n", input, out);
    return 0;
}

int main(void) {
    int failures = 0;
    failures += check("", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    failures += check("abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    failures += check("The quick brown fox jumps over the lazy dog",
                      "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592");
    failures += check("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
                      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
    {
        uint8_t digest[SHA256_DIGEST_SIZE];
        char out[65];
        sha256_ctx ctx;
        sha256_init(&ctx);
        for (int i = 0; i < 1000000; i++) sha256_update(&ctx, (const uint8_t *) "a", 1);
        sha256_final(&ctx, digest);
        hex(digest, out);
        const char *expected = "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0";
        if (strcmp(out, expected) != 0) {
            fprintf(stderr, "FAIL million-a: %s\n", out);
            failures++;
        } else {
            printf("ok  million-a -> %s\n", out);
        }
    }
    return failures == 0 ? 0 : 1;
}
