# Patch hev-socks5-tunnel pour rate limiting global
# Applique les modifications sur hev-config.h, hev-config.c, hev-socks5-session-tcp.c

# ── hev-config.h ──────────────────────────────────────────────────────────────
h = open('hev-socks5-tunnel/src/hev-config.h').read()
h = h.replace(
    'int hev_config_get_misc_limit_nofile (void);',
    'int hev_config_get_misc_limit_nofile (void);\nlong hev_config_get_misc_rate_limit_bps (void);'
)
open('hev-socks5-tunnel/src/hev-config.h', 'w').write(h)
print("hev-config.h OK")

# ── hev-config.c ──────────────────────────────────────────────────────────────
c = open('hev-socks5-tunnel/src/hev-config.c').read()

# Variable globale
c = c.replace(
    'static int limit_nofile = 65535;',
    'static int limit_nofile = 65535;\nstatic long rate_limit_bps = 0;'
)

# Parsing YAML
c = c.replace(
    'else if (0 == strcmp (key, "limit-nofile"))',
    'else if (0 == strcmp (key, "rate-limit-bps"))\n            rate_limit_bps = strtol (value, NULL, 10);\n        else if (0 == strcmp (key, "limit-nofile"))'
)

# Getter
c += '\nlong\nhev_config_get_misc_rate_limit_bps (void)\n{\n    return rate_limit_bps;\n}\n'

open('hev-socks5-tunnel/src/hev-config.c', 'w').write(c)
print("hev-config.c OK")

# ── hev-socks5-session-tcp.c ───────────────────────────────────────────────────
t = open('hev-socks5-tunnel/src/hev-socks5-session-tcp.c').read()

# Ajouter includes + token bucket global après le dernier #include
rate_limit_code = '''
#include "hev-config.h"
#include <time.h>

static long g_tokens = -1;  /* -1 = non initialisé */
static long g_last_refill_ms = 0;

static long
get_time_ms (void)
{
    struct timespec ts;
    clock_gettime (CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void
rate_limit_acquire (int bytes)
{
    long limit = hev_config_get_misc_rate_limit_bps ();
    if (limit <= 0) return;
    long now = get_time_ms ();
    /* Initialisation au premier appel */
    if (g_tokens < 0) {
        g_tokens = 0;
        g_last_refill_ms = now;
    }
    long elapsed = now - g_last_refill_ms;
    if (elapsed >= 10) {
        /* Refill limité à 1 intervalle max pour éviter burst */
        long refill = limit * elapsed / 1000;
        if (refill > limit / 10) refill = limit / 10; /* max 100ms de tokens */
        g_tokens += refill;
        if (g_tokens > limit / 5) g_tokens = limit / 5; /* bucket max = 200ms */
        g_last_refill_ms = now;
    }
    if (g_tokens < bytes) {
        long wait_ms = (bytes - g_tokens) * 1000 / limit + 1;
        struct timespec ts = { wait_ms / 1000, (wait_ms % 1000) * 1000000 };
        nanosleep (&ts, NULL);
        g_tokens = 0;
    } else {
        g_tokens -= bytes;
    }
}
'''

t = t.replace(
    '#include "hev-socks5-session-tcp.h"',
    '#include "hev-socks5-session-tcp.h"' + rate_limit_code
)

# Appeler rate_limit_acquire après readv
t = t.replace(
    'hev_ring_buffer_write_finish (self->buffer, s);',
    'hev_ring_buffer_write_finish (self->buffer, s);\n            rate_limit_acquire (s);'
)

open('hev-socks5-tunnel/src/hev-socks5-session-tcp.c', 'w').write(t)
print("hev-socks5-session-tcp.c OK")
print("Patch rate limiting complet!")
