# 第三方组件声明 / Third-Party Notices

Gal World 本体以 MIT 许可证发布（见 [`LICENSE`](LICENSE)）。本文件列出随发布物分发的第三方组件、其许可证，以及许可证全文所在位置。

本仓库**不包含**游戏本体、汉化补丁、游戏素材；也不包含下述部署期外部组件（Sunshine、Moonlight、Wine、Selkies、VB-Cable）的二进制——这些组件由部署流程在目标机器上从上游或内网镜像获取，按各自许可证使用。

许可证全文存放在 [`licenses/`](licenses/)，并随发布 jar 打进 `META-INF/licenses/`。

---

## 1. 后端（随 `gal-world-portal-*.jar` 分发的 `BOOT-INF/lib/`）

| 组件 | 版本 | 许可证 | 备注 |
|---|---|---|---|
| Spring Boot / Spring Framework（spring-*、spring-boot-*） | 3.3.5 / 6.1.14 | Apache-2.0 | |
| Apache Tomcat Embed（tomcat-embed-core / -el / -websocket） | 10.1.31 | Apache-2.0 | |
| Jackson（jackson-annotations / -core / -databind / -datatype-jdk8 / -datatype-jsr310 / -module-parameter-names） | 2.17.2 | Apache-2.0 | |
| Apache Commons（codec / compress / io / lang3） | 1.16.1 / 1.26.1 / 2.15.1 / 3.14.0 | Apache-2.0 | |
| Apache Log4j API（log4j-api / log4j-to-slf4j） | 2.23.1 | Apache-2.0 | |
| Micrometer（micrometer-commons / -observation） | 1.13.6 | Apache-2.0 | |
| HikariCP | 5.1.0 | Apache-2.0 | |
| byte-buddy | 1.14.19 | Apache-2.0 | |
| classmate | 1.7.0 | Apache-2.0 | |
| jandex | 3.1.2 | Apache-2.0 | |
| jboss-logging | 3.5.3.Final | Apache-2.0 | |
| snakeyaml | 2.2 | Apache-2.0 | |
| hibernate-validator | 8.0.1.Final | Apache-2.0 | |
| jakarta.inject-api | 2.0.1 | Apache-2.0 | |
| jakarta.validation-api | 3.0.2 | Apache-2.0 | |
| **Hibernate ORM（hibernate-core / hibernate-commons-annotations）** | 6.5.3.Final / 6.0.6.Final | **LGPL-2.1** | 以未修改的独立 jar 形式分发，可自行替换；源码见 `licenses/LGPL-2.1.txt` 与 Hibernate 官方仓库 |
| **Logback（logback-core / logback-classic）** | 1.5.11 | **EPL-1.0 或 LGPL-2.1**（双许可） | 本项目按 EPL-1.0 使用；源码可向项目维护者索取 |
| **AspectJ Weaver** | 1.9.22.1 | **EPL-1.0** | 由 `spring-aspects` 引入；以未修改形式分发 |
| **H2 Database Engine** | 2.2.224 | **MPL-2.0 或 EPL-1.0**（双许可） | 本项目按 MPL-2.0 使用 |
| **jakarta.annotation-api** | 2.1.1 | **EPL-2.0 或 GPL-2.0 (Classpath Exception)** | 本项目按 EPL-2.0 使用 |
| **jakarta.transaction-api** | 2.0.1 | **EPL-2.0 或 GPL-2.0 (Classpath Exception)** | 本项目按 EPL-2.0 使用 |
| **jakarta.persistence-api** | 3.1.0 | **EPL-2.0 或 EDL-1.0** | 本项目按 EPL-2.0 使用 |
| jakarta.activation-api | 2.1.3 | EDL-1.0（BSD-3-Clause 变体） | |
| jakarta.xml.bind-api | 4.0.2 | EDL-1.0 | |
| jaxb-core / jaxb-runtime / txw2（Eclipse EE4J） | 4.0.5 | EDL-1.0 | |
| angus-activation | 2.0.2 | EDL-1.0 | |
| istack-commons-runtime | 4.1.2 | EDL-1.0 | |
| antlr4-runtime | 4.13.0 | BSD-3-Clause | ANTLR 4 项目许可 |
| slf4j-api / jul-to-slf4j | 2.0.16 | MIT | |
| thumbnailator | 0.4.8 | MIT | |
| xz（org.tukaani:xz） | 1.9 | Public Domain | 无附加义务 |

**LGPL-2.1 说明**：`hibernate-core` 与 `hibernate-commons-annotations` 以**未修改**的独立 jar 形式嵌套在 `BOOT-INF/lib/` 中分发，接收方可随时用自己构建的版本替换该 jar 并重新打包，无需改动本项目源码。许可证全文见 `licenses/LGPL-2.1.txt`；上游源码可从 <https://github.com/hibernate/hibernate-orm> 与 <https://github.com/hibernate/hibernate-commons-annotations> 获取。若需要本项目所使用确切版本的源码副本，可向维护者提出。

**EPL-1.0 说明**：`logback` 与 `aspectjweaver` 均未经修改，许可证全文见 `licenses/EPL-1.0.txt`。上游源码可从 <https://github.com/qos-ch/logback> 与 <https://github.com/eclipse-aspectj/aspectj> 获取。

---

## 2. 前端（构建产物位于 jar 内 `BOOT-INF/classes/static/assets/`）

| 组件 | 版本 | 许可证 |
|---|---|---|
| Vue | 3.5.44 | MIT |
| vue-router | 4.6.4 | MIT |
| Element Plus | 2.14.7 | MIT |
| @element-plus/icons-vue | 2.3.2 | MIT |
| @vueuse/core / @vueuse/metadata | 15.0.0 | MIT |
| @popperjs/core | 2.11.8 | MIT |
| @ctrl/tinycolor | 4.2.2 | MIT |
| async-validator | 4.2.5 | MIT |
| dayjs | 1.11.23 | MIT |
| lodash / lodash-unified | 4.17.27 / 1.0.3 | MIT |
| normalize-wheel-es | 1.2.0 | BSD-3-Clause |

构建工具（Vite、@vitejs/plugin-vue）仅在构建期使用，不随产物分发。

构建产物保留了 Vue 各子包与 vue-router 的版权注释（`@license MIT`）。MIT 全文见 `licenses/MIT.txt`。

---

## 3. 部署期外部组件（不随本仓库或发布 jar 分发）

以下组件由部署流程在目标机器上从上游或内网镜像获取并独立安装、以独立进程运行；它们不链接进本项目代码，不构成本项目的衍生作品。

| 组件 | 版本 | 许可证 | 获取方式 |
|---|---|---|---|
| Sunshine | 2026.914.233613 | GPL-3.0 | 上游 GitHub Release（LizardByte/Sunshine） |
| moonlight-web-stream | 3.0.1 | GPL-3.0 | 上游 GitHub Release（MrCreativ3001/moonlight-web-stream） |
| Wine（Kron4ek 构建） | 9.0-staging-amd64 | LGPL-2.1+ | 上游 GitHub Release（Kron4ek/Wine-Builds） |
| Selkies | 2.2.0 | MPL-2.0 | 上游 GitHub Release（selkies-project/selkies），pip 安装 |
| VB-Cable（VB-Audio） | 45.0 | Donationware | 上游官方站点，安装前请阅读其 EULA |

**分发边界**：本项目的元件镜像（Nexus `galworld-artifacts` 仓库）仅供部署者本人内网自用，不对外提供下载，因此不构成对上述 GPL-3.0 组件的对外分发。若将该镜像对外开放，需自行满足各许可证的分发义务（GPL-3.0 需随二进制提供对应源码或书面提供承诺）。

---

## 4. 许可证全文

| 许可证 | 文件 |
|---|---|
| MIT | [`licenses/MIT.txt`](licenses/MIT.txt) |
| Apache-2.0 | [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |
| LGPL-2.1 | [`licenses/LGPL-2.1.txt`](licenses/LGPL-2.1.txt) |
| EPL-1.0 | [`licenses/EPL-1.0.txt`](licenses/EPL-1.0.txt) |
| EPL-2.0 | [`licenses/EPL-2.0.txt`](licenses/EPL-2.0.txt) |
| MPL-2.0 | [`licenses/MPL-2.0.txt`](licenses/MPL-2.0.txt) |
| BSD-3-Clause | [`licenses/BSD-3-Clause.txt`](licenses/BSD-3-Clause.txt) |
| EDL-1.0 | [`licenses/EDL-1.0.txt`](licenses/EDL-1.0.txt) |
