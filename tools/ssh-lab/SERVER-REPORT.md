# 实验室服务器接管报告 — 134.175.207.249

采样时间：2026-11-11 12:17 ~ 12:21 (CST)  
方式：SSH 密码登录（ubuntu）+ 只读命令；未安装任何软件、未修改任何配置、未上传文件

## 一、MCP 接入（已配好，重启 harness 生效）

| 项 | 值 |
|---|---|
| harness 配置 | `C:\Users\lenovo\.dsh\profiles\desktop\cordis.patch.yml`（web profile 同样已加） |
| 插件 | `@deepseek-ai/dsh-mcp-client`，条目 id `mcp-lab-ssh` |
| serverName | `labssh` → 工具名形如 `mcp__labssh__exec` |
| 实现 | `C:\Users\lenovo\.dsh\lab-ssh\server.py`（python 3.12 + paramiko，stdio JSON-RPC，无第三方 MCP 依赖） |
| 凭据 | `C:\Users\lenovo\.dsh\lab-ssh\config.json`（host/port/user/password；支持改 privateKeyPath 走密钥） |
| 日志 | `C:\Users\lenovo\.dsh\lab-ssh\lab-ssh.log` |
| 临时通道 | `tools/ssh-lab/remote_exec.py`（同目录 `recon.txt` / `recon2.txt` 为本轮巡检脚本） |

工具集：`exec`（可选 sudo）、`list_dir`、`read_file`、`upload`、`download`、`host_info`。  
已验证：initialize / tools/list / exec / list_dir / read_file / host_info / download 全部返回正常。

## 二、机器现状

- 系统：Ubuntu 24.04.4 LTS (noble)，kernel 6.8.0-138-generic，x86_64
- 主机名：VM-0-15-ubuntu（内网 IP 10.1.0.15，公网 134.175.207.249）
- 硬件：AMD EPYC 7K62，4 vCPU / 3723 MB 内存 / 2 GB swap / 40 GB 系统盘（已用 5.3 G，14%）
- 时区 Asia/Shanghai，NTP 同步中，DNS 走腾讯内网 183.60.83.19 / 183.60.82.98，出网正常（baidu / pypi 均 200）
- 已装：git、python 3.12.3、gcc、make、vim、tmux、screen、curl、wget、htop、unzip
- 未装：node/npm、nginx、docker、java、go
- 服务：只有系统默认。**对外仅 22/tcp**（sshd），其余全是 lo 上的 systemd-resolved:53
- 云厂商组件：`tat_agent`（自动化助手，running）、云端镜/主机安全 yunjing（inactive）、sgagent（inactive）；iptables 存在 `YJ-FIREWALL-INPUT` 链，已封 2 个爆破 IP（45.148.10.164、77.239.124.42）
- 包更新：dpkg 729 个包，**106 个待升级**

## 三、账号与安全面

- 用户：`ubuntu`（uid 1000，有 sudo 免密组权限，实测 sudo 可用）、`lighthouse`（腾讯镜像默认）
- `~/.ssh/authorized_keys` 为空（0 字节）→ 目前只能密码登录
- sshd：`PasswordAuthentication yes`、`PermitRootLogin yes`、`KbdInteractiveAuthentication no`
- ufw：inactive；fail2ban：未安装
- 登录记录：只有你自己的 218.19.96.150（3 个会话）+ 初始化时的 orcaterm 会话

**风险点（未改，等你决定）**
1. root + 密码登录对公网 22 敞开，且密码为弱口令形态 → 必被扫。已有境外 IP 爆破被封说明扫描已经发生。
2. 无 fail2ban / 无 ufw。
3. 106 个待升级包。
4. 建议顺序：装公钥 → 关密码登录 → 关 root 直登 → 改端口或加安全组白名单 → 装 fail2ban → 全量升级。

## 四、足迹

只读巡检。唯一副作用是首次 sudo 生成了 `~/.sudo_as_admin_successful`（0 字节）以及 `/var/log/auth.log` 里的登录记录；没有读写任何业务目录。
