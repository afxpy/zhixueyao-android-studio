import socket, ssl, sys

"""
验证「代理答应建隧道」和「隧道真的能通」是两件事。

用户给我看了系统代理设置：`127.0.0.1:7892`，并说
「**这只是我本地配置的代理，是国内的并不能访问国外网址**」。

而我原来的检测只做到 CONNECT 拿到 200 就返回「能连上」——
那一步只能证明「代理同意建隧道」，**不能证明隧道里跑得通**。

这个脚本把两步分开测，看它们的结果是否真的不同。
"""

def probe(port, host="github.com"):
    print("=== 通过 127.0.0.1:%d 访问 %s ===" % (port, host))
    print()

    # 第 1 步：CONNECT，看它答不答应
    try:
        s = socket.create_connection(("127.0.0.1", port), 6)
        s.settimeout(8)
        s.sendall(("CONNECT %s:443 HTTP/1.1\r\nHost: %s:443\r\n\r\n" % (host, host)).encode())
        line = s.recv(200).decode("ascii", "replace").split("\r\n")[0]
        print("第 1 步 CONNECT 回应：%s" % line)
        if " 200" not in line:
            print("→ 代理直接拒绝了，到此为止")
            return
        print("→ **代理答应了建隧道**（但这不代表能通）")
    except Exception as e:
        print("第 1 步就失败：%r" % e)
        return

    # 第 2 步：在隧道里做一次真实 TLS 握手
    print()
    print("第 2 步：在隧道里做 TLS 握手…")
    try:
        ctx = ssl._create_unverified_context()
        ss = ctx.wrap_socket(s, server_hostname=host)
        print("→ **握手成功**：%s %s" % (ss.version(), ss.cipher()[0]))
        print()
        print("结论：**这个代理真的能访问 %s**" % host)
        ss.close()
    except Exception as e:
        print("→ **握手失败**：%r" % e)
        print()
        print("结论：**代理答应了建隧道，但实际连不通** ——")
        print("      这正是「只看 CONNECT 200 会误判」的现场证据")
    finally:
        try:
            s.close()
        except Exception:
            pass


# 默认测用户的系统代理端口；也可以命令行传别的
port = int(sys.argv[1]) if len(sys.argv) > 1 else 7892
probe(port)
