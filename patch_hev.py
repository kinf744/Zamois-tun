# hev-config.h
h = open('hev-socks5-tunnel/src/hev-config.h').read()
h = h.replace(
    'HevConfigServer *hev_config_get_socks5_server (void);',
    '#define HEV_MAX_SOCKS_SERVERS 8\nHevConfigServer *hev_config_get_socks5_server (void);\nint hev_config_get_socks5_server_count (void);'
)
open('hev-socks5-tunnel/src/hev-config.h', 'w').write(h)

# hev-config.c
c = open('hev-socks5-tunnel/src/hev-config.c').read()

# 1. stdatomic
c = c.replace('#include "hev-config.h"', '#include <stdatomic.h>\n#include <string.h>\n#include "hev-config.h"')

# 2. srv tableau
c = c.replace('static HevConfigServer srv;',
    'static HevConfigServer srv[HEV_MAX_SOCKS_SERVERS];\nstatic int srv_count = 0;\nstatic atomic_int srv_rr = 0;')

# 3. Variables user/pass en tableau
c = c.replace(
    'static char _user[256];\nstatic char _pass[256];',
    'static char _user[HEV_MAX_SOCKS_SERVERS][256];\nstatic char _pass[HEV_MAX_SOCKS_SERVERS][256];'
)

# 4. Remplacer la fin de parse_socks5 pour supporter ports multiples
old_end = '''    strncpy (srv.addr, addr, 256 - 1);
    srv.port = strtoul (port, NULL, 10);

    if (pipe && (strcasecmp (pipe, "true") == 0))
        srv.pipeline = 1;

    if (udpm && (strcasecmp (udpm, "udp") == 0))
        srv.udp_in_udp = 1;

    if (udpa)
        strncpy (srv.udp_addr, udpa, 256 - 1);

    if (user && pass) {
        strncpy (_user, user, 256 - 1);
        strncpy (_pass, pass, 256 - 1);
        srv.user = _user;
        srv.pass = _pass;
    }

    if (mark)
        srv.mark = strtoul (mark, NULL, 0);'''

new_end = '''    /* Support multiple ports: addr can be "host:p1,p2,p3" or port field "p1,p2,p3" */
    {
        char port_buf[256];
        strncpy (port_buf, port, 255);
        char *tok = strtok (port_buf, ",");
        while (tok && srv_count < HEV_MAX_SOCKS_SERVERS) {
            strncpy (srv[srv_count].addr, addr, 255);
            srv[srv_count].port = strtoul (tok, NULL, 10);
            if (pipe && (strcasecmp (pipe, "true") == 0))
                srv[srv_count].pipeline = 1;
            if (udpm && (strcasecmp (udpm, "udp") == 0))
                srv[srv_count].udp_in_udp = 1;
            if (udpa)
                strncpy (srv[srv_count].udp_addr, udpa, 255);
            if (user && pass) {
                strncpy (_user + srv_count * 256, user, 255);
                strncpy (_pass + srv_count * 256, pass, 255);
                srv[srv_count].user = _user + srv_count * 256;
                srv[srv_count].pass = _pass + srv_count * 256;
            }
            if (mark)
                srv[srv_count].mark = strtoul (mark, NULL, 0);
            srv_count++;
            tok = strtok (NULL, ",");
        }
    }'''

if old_end in c:
    c = c.replace(old_end, new_end)
    print("Patch end OK!")
else:
    print("ERROR: end pattern not found!")

# 5. round-robin
c = c.replace(
    'hev_config_get_socks5_server (void)\n{\n    return &srv;\n}',
    'hev_config_get_socks5_server (void)\n{\n    if (srv_count <= 1) return &srv[0];\n    int idx = atomic_fetch_add (&srv_rr, 1) % srv_count;\n    return &srv[idx];\n}\n\nint\nhev_config_get_socks5_server_count (void)\n{\n    return srv_count;\n}'
)

open('hev-socks5-tunnel/src/hev-config.c', 'w').write(c)
print("Patch OK! srv_count:", c.count("srv_count"))
