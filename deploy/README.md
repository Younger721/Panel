# deploy/ 目录说明

```
deploy/
├── systemd/panel-backend@.service     蓝绿两个实例共用（%i = blue|green）
├── env/
│   ├── panel.env.example              → /etc/panel/panel.env（两色共用）
│   ├── blue.env.example               → /etc/panel/blue.env（8080 + PANEL_JAR）
│   └── green.env.example              → /etc/panel/green.env（8081 + PANEL_JAR）
├── nginx/
│   ├── panel.conf                     主机版站点配置
│   ├── panel-upstream.conf.example    → /etc/nginx/panel-upstream.conf（切流目标）
│   ├── panel.container.conf           容器版站点配置
│   └── panel-upstream.container.conf  容器版 upstream
├── scripts/
│   ├── release.sh                     蓝绿发布 + 迁移前备份 + 冒烟
│   ├── rollback.sh                    切回指定颜色
│   ├── switch-upstream.sh             只切流（会先探活）
│   ├── smoke.sh                       只读冒烟（health/login/列表接口）
│   └── backup.sh                      db-only / all
├── backend.Dockerfile                 仅容器方案使用
└── docker-compose.prod.yml            可选：换机器 / 扩容时用
```

安装（主机方案）：

```bash
sudo install -m 0644 deploy/systemd/panel-backend@.service /etc/systemd/system/
sudo systemctl daemon-reload

sudo install -d /etc/panel
sudo install -m 600 deploy/env/panel.env.example  /etc/panel/panel.env
sudo install -m 600 deploy/env/blue.env.example   /etc/panel/blue.env
sudo install -m 600 deploy/env/green.env.example  /etc/panel/green.env
# 然后编辑 3 个文件里的 CHANGE_ME 与 PANEL_JAR

sudo install -m 0644 deploy/nginx/panel.conf /etc/nginx/sites-available/panel
sudo ln -sfn /etc/nginx/sites-available/panel /etc/nginx/sites-enabled/panel
sudo install -m 0644 deploy/nginx/panel-upstream.conf.example /etc/nginx/panel-upstream.conf

sudo install -d -m 0755 /opt/panel/{releases,data/knowledge,backup,logs}
sudo chown -R panel:panel /opt/panel
sudo install -m 0755 -o root -g root deploy/scripts/*.sh /opt/panel/deploy/scripts/
```

首次启动：

```bash
sudo systemctl enable --now panel-backend@blue
sudo curl -s localhost:8080/actuator/health
```

发布：

```bash
# 本地构建后
scp backend/target/panel-backend-*.jar server:/tmp/panel-backend.jar
ssh server 'sudo /opt/panel/deploy/scripts/release.sh /tmp/panel-backend.jar 20261113-a1b2c3d'
```
