<p align="center">
  <img src="images/robonix-logo.svg" alt="Robonix" width="420" />
</p>

<h3 align="center">Robonix — The Embodied AI Operating System</h3>

<p align="center">
  <em>A system substrate for building embodied intelligence across heterogeneous robots.</em>
</p>

<p align="center">
  <a href="https://github.com/syswonder/robonix/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-MulanPSL--2.0-red?style=flat-square" alt="License" /></a>
  <a href="https://github.com/syswonder/robonix/graphs/contributors"><img src="https://img.shields.io/github/contributors/syswonder/robonix?color=blue&style=flat-square" alt="Contributors" /></a>
  <img src="https://img.shields.io/github/languages/code-size/syswonder/robonix?color=green&style=flat-square" alt="Code size" />
  <img src="https://img.shields.io/github/repo-size/syswonder/robonix?color=lightgray&style=flat-square" alt="Repo size" />
  <img src="https://img.shields.io/github/languages/top/syswonder/robonix?color=orange&style=flat-square" alt="Top language" />
  <a href="https://packages.robonix.ai/packages/"><img src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fsyswonder.github.io%2Frobonix-package-catalog%2Fapi%2Fv1%2Fpackages&query=%24.packages.length&label=Robonix%20packages&color=0f766e&style=flat-square" alt="Robonix packages" /></a>
  <a href="#supported-robots"><img src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fsyswonder.github.io%2Frobonix-package-catalog%2Fapi%2Fv1%2Frobots&query=%24.robots.length&label=Robot%20deployments&color=2563eb&style=flat-square" alt="Published robot deployments" /></a>
</p>

<br />

<br />

> ## 本 Fork（nbsheep/robonix）—— 快速复现 / Quick Walkthrough
>
> 这是 [syswonder/robonix](https://github.com/syswonder/robonix) 的一个 fork，除官方 RoboNIX 系统外**额外加入**了两样东西：
> 1. **`rbnx call`** —— 直接调用单个能力原语，绕过 LLM / Pilot，确定、可脚本化（官方没有）。
> 2. **大疆 M3E 无人机接入** —— 一套 `drone_bridge` 原语（`takeoff` / `land` / `move_velocity` / `state` 等 11 个），工程在 companion 仓库里。
>
> **⚠️ 新手最容易踩的坑：** `git clone` 默认拿到的是 **`main`** 分支，而 **`main` 上没有 `rbnx call`**（`call` / `ask` 只在 `dev`）。请务必切到 `dev`。
>
> | 分支 | `rbnx call` / `ask` | 无人机原语 | 说明 |
> |---|---|---|---|
> | `main`（默认，clone 拿到） | ❌ | — | 落后/空态，**要切走** |
> | `dev` | ✅ | — | **主线：RoboNIX OS + `rbnx call`** |
> | `master` | ❌ | ✅ | 同事的无人机线（drone_bridge / UAVtest） |
>
> ### ① 克隆并切到 `dev`
> ```bash
> git clone https://github.com/nbsheep/robonix.git
> cd robonix
> git checkout dev
> ```
>
> ### ② 构建（与官方一致）
> ```bash
> curl -LsSf https://astral.sh/uv/install.sh | sh
> export PATH="$HOME/.local/bin:$HOME/.cargo/bin:$PATH"
> make install
> ```
>
> ### ③ 用一个部署目录把 RoboNIX 跑起来
> `rbnx call` / `ask` 需要 atlas + executor 在跑。最快是官方 Webots 仿真（无需硬件），或接入无人机后走你的部署目录：
> ```bash
> # 方式 A：官方 Webots 仿真（无硬件，先验证 call/ask 通）
> cd examples/webots && rbnx build && rbnx boot
> # 方式 B：接入 M3E 无人机后，在你的部署目录（见下方 ⑤）
> cd ~/my-robot && rbnx boot
> ```
>
> ### ④ 验证并使用 `rbnx call` / `rbnx ask`
> ```bash
> # 确认子命令存在（call / ask 要能打印出来）
> rbnx --help | grep -E "call|ask"
>
> # ask：自然语言，走 LLM，体验式
> rbnx ask "你现在能用哪些能力？"
>
> # call：绕过 LLM 直接调一个原语，确定、适合真实控制/硬件验收
> rbnx call robonix/primitive/drone/state                 # 无参、安全，先验证链路
> rbnx call robonix/primitive/drone/takeoff --args '{"altitude": 3.0}'
> ```
> - `rbnx call` 通用形式：`rbnx call <contract_id> [--provider <id>] [--args '<json>'] [--server <ep>]`
> - `rbnx ask` 通用形式：`rbnx ask "<prompt>" [--server <ep>]`
> - ⚠️ 带参调用要把该原语 `config.spec` 里的字段**写全**，缺一个会报 `field required`。
>
> ### ⑤ 可选项：接入大疆 M3E 无人机
> 上面的 `rbnx call robonix/primitive/drone/*` 调的就是无人机原语。要让它们注册进 RoboNIX，得先部署 companion 工程 **`drone_bridge`**（UI / 驱动 / 11 个 drone 原语都在里面）：
> ```bash
> cp -r drone_bridge ~/my-robot/primitives/
> cd ~/my-robot/primitives/drone_bridge
> bash scripts/build.sh                       # rbnx codegen --mcp 生成 drone_mcp
> nano ~/my-robot/robonix_manifest.yaml       # 把 rc_pro_ip 改成你的遥控器 IP
> cd ~/my-robot && rbnx boot
> rbnx caps | grep drone                      # 应看到 drone_bridge [ACTIVE] (11 caps)
> ```
> 完整原语清单、每个参数、调用判据，见 drone_bridge 工程里的 **`docs/从零开始_完整操作手册.md`**。
>
> ### ⑥ 可选：给无人机加"自动巡航火灾检测"（对准拍摄 + 报告）
> 检测跑**电脑端**（离板）：无人机巡航时对实况画面做 YOLO 火/烟检测，发现火→云台对准 + 拍照 + 打 GPS + 写报告，**不带任何飞行控制命令**。一份可运行示例在 **`examples/drone_fire_patrol/`**（含最佳权重 `models/best.pt`、云台对准控制器 `gimbal_aim.py`、去抖器/巡检报告；用法与安全须知见其 README）。
>
> 下方是官方 syswonder/robonix 的原始 README（系统介绍 / 架构 / Webots / 包模型等），对理解系统本身仍然有用。

## Robonix

Robonix is an operating system for embodied intelligence. It explores how to
construct a robot's "brain" at the system level: a common substrate on which
models can perceive, understand, plan, and act through heterogeneous robot
bodies without being rewritten around every vendor SDK.

Robonix treats AI models and skills as programs and exposes robot hardware as
discoverable capabilities. This separation lets robot developers integrate a
body once, while model and skill developers build against shared interfaces for
cameras, lidar, chassis, arms, mapping, navigation, speech, and other
capabilities. The long-term goal is simple: **train once, deploy on any robot**.

> [!WARNING]
> Robonix is in early development. APIs and internal designs may change before
> a versioned release.

## Supported Robots

| Robot | Integrated hardware | Maintained by | Deployment | Catalog |
| --- | --- | --- | --- | --- |
| AgileX Ranger Mini v3 | Ranger Mini v3 chassis; Livox MID-360 lidar and IMU; Intel RealSense D435i RGB-D camera; optional AgileX Piper arm; audio | syswonder | [link](https://github.com/syswonder/robot-agilex-ranger_mini_v3) | [link](https://packages.robonix.ai/robots/robonix.robot.agilex.ranger_mini_v3/) |
| DEEP Robotics Lite3 | Lite3 quadruped chassis; Livox MID-360 lidar and IMU; Orbbec Gemini 330-series RGB-D camera | [Bunnycxk](https://github.com/Bunnycxk) | [link](https://github.com/syswonder/robot-deep_robotics-lite3) | [link](https://packages.robonix.ai/robots/robonix.robot.deep_robotics.lite3/) |
| WHEELTEC R550 mini_tank | R550 tracked chassis and IMU; LSLIDAR N10P; Orbbec Astra S RGB-D camera | [sherry-part](https://github.com/sherry-part) | [link](https://github.com/syswonder/robot-wheeltec-r550) | [link](https://packages.robonix.ai/robots/robonix.robot.wheeltec.r550/) |
| Unitree Go2 | Go2 quadruped chassis; onboard lidar, camera, and IMU; audio bridge | [Origamii520](https://github.com/Origamii520) | [link](https://github.com/syswonder/robot-unitree-go2) | [link](https://packages.robonix.ai/robots/robonix.robot.unitree.go2/) |
| WowRobo Roboarm | Five-axis LeRobot Koch arm; Orbbec Gemini 215 RGB-D camera; audio | [gaoyz1235](https://github.com/gaoyz1235) | [link](https://github.com/syswonder/robot-wowrobo-roboarm) | [link](https://packages.robonix.ai/robots/robonix.robot.wowrobo.roboarm/) |
| Webots TIAGo Lite (simulation) | Simulated differential-drive chassis; head RGB-D camera; Hokuyo planar lidar; audio | syswonder | [link](examples/webots/) | — |
| Minecraft Bot (simulation) | Minecraft player body; camera, chassis, world-state, inventory, navigation, and exploration providers | [ZZJJWarth](https://github.com/ZZJJWarth) | [link](https://github.com/syswonder/robot-syswonder-minecraft_bot) | [link](https://packages.robonix.ai/robots/robonix.robot.syswonder.minecraft_bot/) |

Each deployment links the complete robot manifest and its primitive, service,
and skill dependencies. Published deployment metadata does not replace the
hardware-specific safety, commissioning, and acceptance gates documented by
each repository. See the
[robot catalog](https://packages.robonix.ai/robots/)
for published integrations.

## Quick Start

Install Robonix:

```bash
git clone --recursive --branch dev https://github.com/syswonder/robonix.git
cd robonix
curl -LsSf https://astral.sh/uv/install.sh | sh
export PATH="$HOME/.local/bin:$HOME/.cargo/bin:$PATH"
make install
```

Start the Webots simulator in one terminal:

```bash
export DISPLAY=:0
bash examples/webots/sim/start.sh
```

Boot Robonix in a second terminal with any OpenAI-compatible VLM endpoint:

```bash
export RMW_IMPLEMENTATION=rmw_zenoh_cpp
export VLM_BASE_URL=https://api.openai.com/v1
export VLM_API_KEY=sk-...
export VLM_MODEL=your-model-name

cd examples/webots
rbnx build
rbnx boot
```

Then run `rbnx chat` in a third terminal. Try `go to room 101`, `what can you
see?`, or `explore the office`. See the
[Getting Started guide](https://robonix-book.syswonder.org/getting-started/quickstart)
for the complete walkthrough.

https://github.com/user-attachments/assets/604b2c7f-3b6d-46be-858b-c52acaf686e3

## Services, Skills, and Robot Integrations

Robonix grows in two directions: reusable services and skills above the system
layer, and reusable robot integrations below it. Both use the same package and
capability model, so a model or skill depends on what a robot can do rather
than on a particular vendor implementation.

### Services and skills

A **service** provides a general capability that many models and skills can
reuse, such as mapping, navigation, memory, speech, or voice identification.
Services own their runtime state and interfaces, and a deployment can replace
one implementation without changing the skills that consume it.

A **skill** is a task-facing package and is the closest Robonix equivalent to an
application in the operating-system analogy. It may orchestrate several
services and primitives, wrap a learned VLA policy, or combine model inference
with scripts and task-specific logic. Exploration, greeting people,
transporting an object, and grasp-and-place behaviors are examples of skills.
Skills remain independently installable and can be activated only when a task
needs them.

Services and skills declare capabilities through Atlas. Pilot can select those
capabilities while planning, and Executor dispatches the resulting RTDL nodes
while preserving per-task state, concurrency, and cancellation. Browse current
drivers, services, and skills in the
[package catalog](https://packages.robonix.ai/packages/).
The package badge above is updated from the
[catalog API](https://packages.robonix.ai/api/v1/packages).

### Hardware and robot deployments

A **primitive** adapts one physical device, such as a camera, lidar, chassis,
arm, gripper, or audio device, to Robonix capability contracts. A robot
deployment repository assembles these primitives with the complete body
description, selected services and skills, and runtime configuration for one
platform. This gives models and skills one consistent view of each robot.
Browse complete integrations in the
[robot catalog](https://packages.robonix.ai/robots/).

### Build services and skills

[template-rbnx](https://github.com/syswonder/template-rbnx) provides a minimal
service and skill package that can be built and booted without robot hardware.
Use it to define capability interfaces, configuration, lifecycle hooks, and
package metadata before connecting the package to a real deployment.

[Robonix Skill Toolkit](https://github.com/zhengzihaoPKU/Robonix-Skill-Toolkit)
supports the VLA skill workflow: collect teleoperation data, fine-tune an
[OpenVLA-OFT](https://openvla-oft.github.io) policy, and deploy the resulting
skill on a real robot arm such as the
[AgileX Piper](https://github.com/agilexrobotics/Agilex-College).

## Why an Operating System?

Robonix is more than a set of robot APIs. It provides the shared execution
environment in which models, skills, services, and hardware providers coexist.
Models and skills use common capability contracts, while the system handles
discovery, body and environment state, task execution, provider lifecycle,
policy checks, cancellation, and structured history.

In the operating-system analogy, models and skills are programs and robot
capabilities are resources. Long-running and concurrent plans have explicit
identity and state, so they can be observed, steered, and cancelled without
embedding these mechanisms independently in every skill.

## System architecture

The system components below keep planning, execution, state, communication,
health, and safety separate from individual hardware drivers and skills.

| Component                        | Responsibility                                                                          |
| -------------------------------- | --------------------------------------------------------------------------------------- |
| **[atlas](system/atlas/)**       | Capability registry and discovery: the catalog of every registered capability and its contract |
| **[chronos](system/chronos/)**   | Unified clock and cross-sensor timestamp alignment (PTP / IEEE-1588)                     |
| **[executor](system/executor/)** | RTDL plan execution and capability dispatch (`sequence` / `parallel` / `do`)             |
| **[keystone](system/keystone/)** | User identity, persistent configuration, and access policy                              |
| **[liaison](system/liaison/)**   | Human–machine interaction gateway: chat, voice, and TUI                                 |
| **[nexus](system/nexus/)**       | Communication libraries for gRPC / MCP / ROS 2 (not a standalone process)               |
| **[pilot](system/pilot/)**       | VLM-driven planning and decision loop; emits RTDL plans for the executor                 |
| **[scene](system/scene/)**       | Live environment estimate: object registry, semantic relations, and occupancy grid     |
| **[scribe](system/scribe/)**     | Structured, persistent, replayable system journal for audit                             |
| **[sentinel](system/sentinel/)** | Rule-based safety gate checked before each capability dispatch                          |
| **[soma](system/soma/)**         | Robot self-description (body model): device topology and primitive abstraction          |
| **[vitals](system/vitals/)**     | Robot power and component-health monitoring                                             |

Robot-facing and model- or skill-facing packages fall into three open categories.
They implement shared contracts from [`capabilities/`](capabilities/) and may be
replaced independently in each deployment:

* **primitive** — one device per package (camera, lidar, chassis, arm). Lives
  in deployment repos and per-example folders (e.g. `examples/webots/primitives/`).
* **service** — runtime functionality (mapping, navigation, semantic map,
  memory, speech, voiceprint). Default reference implementations ship in
  [`services/`](services/); each can be swapped out by a deployment.
* **skill** — user-defined reusable execution flows (grasp, place, explore,
  fold-clothes …). Lives wherever the deploy/integrator wants.

## Supported platforms

| Arch    | OS / Distribution                                  | Status     |
| ------- | -------------------------------------------------- | ---------- |
| x86\_64 | Ubuntu 22.04                                       | ✅ Tested  |
| x86\_64 | Debian 13                                          | ✅ Tested  |
| arm64   | NVIDIA Jetson — JetPack 6.2 (L4T 36.4.3, Ubuntu 22.04) | ✅ Tested  |
| x86\_64 / arm64 | Ubuntu 24.04 and newer                     | 🚧 Planned |
| x86\_64 / arm64 | Arch Linux                                 | 🚧 Planned |
| arm64   | macOS                                              | 🚧 Planned |

"Tested" means the full Robonix pipeline runs end-to-end on that platform —
in simulation or on a real robot: voice & interaction, task execution, body
movement, scene & mapping (semantic map + spatial map), navigation, and skill
execution. Other Linux distributions will likely work but are not regularly
verified.

**Relationship with ROS 2.** Robonix itself does not depend on ROS 2 — it is
one of the transports nexus offers, not a requirement of the system. If a
capability provider needs the ROS 2 communication libraries and the host OS
has no ROS 2 support, run that provider in a Docker container. Within a single
Robonix deployment, all ROS 2-based capability providers must use the same
ROS 2 distribution (Foxy / Humble / Jazzy); **Humble is recommended**.

## Webots Environments

The simulator launcher supports multiple built-in `.wbt` environments. Select one
explicitly with `--world` or `ROBONIX_WEBOTS_WORLD`:

```bash
bash examples/webots/sim/start.sh --world office.wbt
bash examples/webots/sim/start.sh --world apartment.wbt
ROBONIX_WEBOTS_WORLD=break_room.wbt bash examples/webots/sim/start.sh
```

Available worlds in `examples/webots/sim/ros_ws/src/eaios_webots/worlds/`:
`office.wbt`, `apartment.wbt`, `complete_apartment.wbt`, `break_room.wbt`, and
`kitchen.wbt`.

`office.wbt` is the fully seeded default and is recommended for a first run.
Other worlds may need Cyberbotics' offline asset bundle on their first launch:

```bash
ROBONIX_WEBOTS_DOWNLOAD_ALL_ASSETS=1 \
  bash examples/webots/sim/start.sh --world apartment.wbt
```

The launcher caches the bundle in the `webots_cache` Docker volume. Network
mirror overrides are documented in the
[Webots example](examples/webots/README.md).

|  |  |
|---|---|
| `office.wbt`<br>![office](examples/webots/sim/thumbnails/office.jpg) | `apartment.wbt`<br>![apartment](examples/webots/sim/thumbnails/apartment.jpg) |
| `complete_apartment.wbt`<br>![complete apartment](examples/webots/sim/thumbnails/complete_apartment.jpg) | `break_room.wbt`<br>![break room](examples/webots/sim/thumbnails/break_room.jpg) |
| `kitchen.wbt`<br>![kitchen](examples/webots/sim/thumbnails/kitchen.jpg) |  |

Stop the example with `rbnx shutdown` from `examples/webots`, followed by
`bash examples/webots/sim/stop.sh` from the repository root.

## Quick Development

The [Robonix package template](https://github.com/syswonder/template-rbnx)
contains a mock primitive, a service, and a skill that boot without robot
hardware:

```bash
git clone https://github.com/syswonder/template-rbnx.git
cd template-rbnx
cp .env.example .env
# Fill in the three VLM values in .env.
set -a; source .env; set +a
rbnx build
rbnx boot
```

Run `rbnx caps` to inspect the live providers, then try `rbnx chat` and ask the
robot to say hello. Each example package keeps its manifest, `config.spec`,
build/start scripts, implementation, and optional capability definitions in
one directory. Start there, then follow the
[package integration guide](https://robonix-book.syswonder.org/integration-guide/package-catalog)
to publish a reusable package.

## Repository Layout

```
system/         system components, one directory each
services/       default reference service implementations (memsearch, voiceprint, speech)
pylib/          Python SDK (robonix-api on PyPI)
capabilities/   contract TOMLs + ROS-style IDL tree (capabilities/lib/)
tools/          dev tooling — rbnx CLI + codegen
examples/       end-to-end deployments (webots, test_ci)
docs/           mdBook developer guide (submodule)
Cargo.toml      workspace for the Rust components (4 in system/, 2 in tools/)
Makefile        top-level orchestrate (build / install / fmt / check)
```

`system/<name>/` and `services/<name>/` and `tools/<name>/` are each
self-contained packages — Rust ones carry their own `Cargo.toml`, Python
ones their own `pyproject.toml`. There is no top-level `rust/` /
`python/` split; the runtime role is what determines where a component
lives, not the implementation language.

## Learn More

Dive deeper:

* [**Overview**](https://robonix-book.syswonder.org/architecture/components) — control plane, one full request end-to-end
* [**Namespaces & contracts**](https://robonix-book.syswonder.org/architecture/namespace-and-contracts) — how `robonix/primitive/*` / `robonix/service/*` / `robonix/skill/*` / `robonix/system/*` work
* [**Interface catalog**](https://robonix-book.syswonder.org/interface-catalog/) — every primitive + service contract

## Package Model

Robonix is built from small, swappable **packages**, each implementing one or
more capability contracts normally grouped under a primary
`robonix/<kind>/<area>/*` namespace. Shared framework contracts may be
implemented across those provider namespaces. Namespace mismatches are
diagnostic rather than a runtime authorization boundary. The contract
definitions are documented in the
[interface catalog](https://robonix-book.syswonder.org/interface-catalog/).

This repository contains Robonix system components, built-in reference services, and
examples such as Webots/Tiago. Reusable community packages are indexed by the
[Robonix Package Catalog](https://packages.robonix.ai/);
their source stays in separate package repositories instead of being duplicated
here.

### Built-in services — [`services/`](services/)

| Package | Namespace | What it does |
|---|---|---|
| [`memsearch`](services/memsearch) | `robonix/service/memory/*` | Long-term fact / preference memory; the planner queries it for relevant past context. |
| [`speech`](services/speech) | `robonix/service/speech/*` | Voice I/O — ASR, TTS (incl. streaming), dialog, speaker listing. |
| [`voiceprint`](services/voiceprint) | `robonix/service/voiceprint/*` | Speaker identification (ECAPA-TDNN) — enroll / identify / list / delete. |

> `scene` (3D scene graph), `atlas`, `executor`, `pilot`, and `liaison` are
> **system** components under [`system/`](system/), not services.

### External packages

Use the [Robonix Package Catalog](https://packages.robonix.ai/)
to find reusable primitive, service, and skill packages maintained outside this
repository. The catalog also exposes a machine-readable static JSON API:

| Method | Path | Parameters |
| --- | --- | --- |
| `GET` | `https://packages.robonix.ai/api/v1/packages` | none |
| `GET` | `https://packages.robonix.ai/api/v1/search` | none; filter client-side |
| `GET` | `https://packages.robonix.ai/api/v1/package/<package-name>` | `package-name` is the exact `package.name`, URL-encoded |

Example:

```js
const base = 'https://packages.robonix.ai/api/v1';
const catalog = await fetch(`${base}/packages`).then(r => r.json());
const mapping = await fetch(`${base}/package/${encodeURIComponent('robonix.service.mapping')}`)
  .then(r => r.json());
```

Repository naming follows the catalog convention:

- `primitive-[company]-[model]-[primitive_type]-rbnx` for primitive packages.
- `service-[service_namespace]-rbnx` for service packages.
- `skill-[skill_namespace]-rbnx` for skill packages.

To contribute a community package:

1. Put the package source in its own GitHub repository. The repository root
   must contain `package_manifest.yaml`.
2. In `package_manifest.yaml`, provide catalog metadata under `package`:
   `name`, `version`, `description`, `tags`, and `maintainers`.
   `maintainers` is a list of `Name <email@domain>` entries.
3. Open a pull request to
   [`syswonder/robonix-package-catalog`](https://github.com/syswonder/robonix-package-catalog)
   and add only `name` + `repo` to `catalog.yaml`. The catalog CI fetches the
   package manifest from GitHub, validates the name and metadata, generates the
   website/API, and deploys it to GitHub Pages.

## ROS 2 and Zenoh

ROS 2 is an optional Robonix transport rather than a dependency of the system
itself. Providers that use ROS 2 may run natively or in containers, but all of
them in one deployment must use the same ROS 2 distribution and RMW
implementation. Humble is currently recommended for robot deployments.

The Webots deployment uses `rmw_zenoh_cpp` and starts one `rmw_zenohd` router
for its multi-container ROS graph. Primitive, service, and skill code continues
to use normal ROS 2 APIs; the deployment selects the RMW through its process
environment. See the
[`rmw_zenoh` design](https://github.com/ros2/rmw_zenoh/blob/rolling/docs/design.md)
and the [robot integration guide](https://robonix-book.syswonder.org/integration-guide/vendor-onboarding)
for topology and deployment details.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the repository's license headers,
code style, validation commands, commit format, human-authorship policy, and AI
assistance disclosure rules.

## Contributors

[![All Contributors](https://img.shields.io/github/all-contributors/syswonder/robonix?color=ee8449&style=flat-square)](#contributors)

Thanks goes to these wonderful people:

<!-- ALL-CONTRIBUTORS-LIST:START - Do not remove or modify this section -->
<!-- prettier-ignore-start -->
<!-- markdownlint-disable -->
<table>
  <tbody>
    <tr>
      <td align="center" valign="top" width="20%"><a href="https://github.com/enkerewpo"><img src="https://avatars.githubusercontent.com/u/17263645?v=4?s=80" width="80px;" alt="wheatfox"/><br /><sub><b>wheatfox</b></sub></a><br /><a href="https://github.com/syswonder/robonix/commits?author=enkerewpo" title="Code">💻</a> <a href="#maintenance-enkerewpo" title="Maintenance">🚧</a></td>
      <td align="center" valign="top" width="20%"><a href="https://github.com/HustWolfzzb"><img src="https://avatars.githubusercontent.com/u/19464597?v=4?s=80" width="80px;" alt="Zhaobo Zhang"/><br /><sub><b>Zhaobo Zhang</b></sub></a><br /><a href="https://github.com/syswonder/robonix/commits?author=HustWolfzzb" title="Code">💻</a></td>
      <td align="center" valign="top" width="20%"><a href="https://github.com/KouweiLee"><img src="https://avatars.githubusercontent.com/u/98637586?v=4?s=80" width="80px;" alt="Guowei Li"/><br /><sub><b>Guowei Li</b></sub></a><br /><a href="https://github.com/syswonder/robonix/commits?author=KouweiLee" title="Code">💻</a></td>
      <td align="center" valign="top" width="20%"><a href="https://github.com/ken4647"><img src="https://avatars.githubusercontent.com/u/87317372?v=4?s=80" width="80px;" alt="wuzheng"/><br /><sub><b>wuzheng</b></sub></a><br /><a href="https://github.com/syswonder/robonix/commits?author=ken4647" title="Code">💻</a></td>
      <td align="center" valign="top" width="20%"><a href="https://github.com/kaileliu"><img src="https://avatars.githubusercontent.com/u/157936297?v=4?s=80" width="80px;" alt="Kaile Liu"/><br /><sub><b>Kaile Liu</b></sub></a><br /><a href="https://github.com/syswonder/robonix/commits?author=kaileliu" title="Code">💻</a></td>
    </tr>
    <tr>
      <td align="center" valign="top" width="20%"><a href="https://github.com/HeartLinked"><img src="https://avatars.githubusercontent.com/u/78212101?v=4?s=80" width="80px;" alt="Feiyang Li"/><br /><sub><b>Feiyang Li</b></sub></a><br /><a href="https://github.com/syswonder/robonix/commits?author=HeartLinked" title="Code">💻</a></td>
      <td align="center" valign="top" width="20%"><a href="https://github.com/1mujue"><img src="https://avatars.githubusercontent.com/u/115391890?v=4?s=80" width="80px;" alt="MuJue"/><br /><sub><b>MuJue</b></sub></a><br /><a href="https://github.com/syswonder/robonix/commits?author=1mujue" title="Code">💻</a></td>
      <td align="center" valign="top" width="20%"><a href="https://github.com/LittleRookie1115"><img src="https://avatars.githubusercontent.com/u/157590849?v=4?s=80" width="80px;" alt="Zhenyu Zhang"/><br /><sub><b>Zhenyu Zhang</b></sub></a><br /><a href="https://github.com/syswonder/robonix/commits?author=LittleRookie1115" title="Code">💻</a></td>
      <td align="center" valign="top" width="20%"><a href="https://github.com/lhw2002426"><img src="https://avatars.githubusercontent.com/u/75192950?v=4?s=80" width="80px;" alt="lhw2002426"/><br /><sub><b>lhw2002426</b></sub></a><br /><a href="https://github.com/syswonder/robonix/commits?author=lhw2002426" title="Code">💻</a></td>
      <td align="center" valign="top" width="20%"><a href="https://github.com/zhengzihaoPKU"><img src="https://avatars.githubusercontent.com/u/141690701?v=4?s=80" width="80px;" alt="Zihao Zheng"/><br /><sub><b>Zihao Zheng</b></sub></a><br /><a href="#tool-zhengzihaoPKU" title="Tools">🔧</a> <a href="#data-zhengzihaoPKU" title="Data">🔣</a></td>
    </tr>
    <tr>
      <td align="center" valign="top" width="20%"><a href="https://github.com/QingFeng34048"><img src="https://avatars.githubusercontent.com/u/202889188?v=4?s=80" width="80px;" alt="qingfeng123"/><br /><sub><b>qingfeng123</b></sub></a><br /><a href="#tool-QingFeng34048" title="Tools">🔧</a> <a href="#data-QingFeng34048" title="Data">🔣</a></td>
      <td align="center" valign="top" width="20%"><a href="https://github.com/ohhhHwH"><img src="https://avatars.githubusercontent.com/u/76088492?v=4?s=80" width="80px;" alt="longyunhou"/><br /><sub><b>longyunhou</b></sub></a><br /><a href="https://github.com/syswonder/robonix/commits?author=ohhhHwH" title="Code">💻</a></td>
      <td align="center" valign="top" width="20%"><a href="https://github.com/Origamii520"><img src="https://avatars.githubusercontent.com/u/214653863?v=4?s=80" width="80px;" alt="Origamii520"/><br /><sub><b>Origamii520</b></sub></a><br /><a href="https://github.com/syswonder/robonix/commits?author=Origamii520" title="Code">💻</a></td>
      <td align="center" valign="top" width="20%"><a href="https://github.com/Bunnycxk"><img src="https://avatars.githubusercontent.com/u/41613268?v=4?s=80" width="80px;" alt="Xiankun Chen"/><br /><sub><b>Xiankun Chen</b></sub></a><br /><a href="https://github.com/syswonder/robonix/commits?author=Bunnycxk" title="Code">💻</a></td>
    </tr>
  </tbody>
</table>

<!-- markdownlint-restore -->
<!-- prettier-ignore-end -->

<!-- ALL-CONTRIBUTORS-LIST:END -->

This project follows the [all-contributors](https://github.com/all-contributors/all-contributors) specification. Contributions of any kind welcome!

## License

Mulan Permissive Software License, Version 2 (MulanPSL-2.0). See [LICENSE](LICENSE).
