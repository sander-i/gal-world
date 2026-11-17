<!--
  SPDX-License-Identifier: MIT
  Copyright (c) 2026 sander
-->

<script setup>
import { t, useI18n } from '../i18n.js'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, getWriteToken, setWriteToken as persistWriteToken } from '../api'
import { applySettings } from '../feature-flags'
import { notify } from '../feedback'
import ThemePicker from '../components/ThemePicker.vue'

/** 语言切换：写 localStorage 并重新拉取消息表，页面即时生效 */
const { locale, languages, currentName, loading: i18nLoading, loadError, setLocale } = useI18n()

/**
 * 语言按钮上的文字。
 *
 * 必须用该语言自己的写法（nativeName），不能跟着当前界面语言翻译：
 * 界面已是日文时，「简体中文」按钮若显示成「簡体字中国語」，看不懂日文
 * 的中文用户就找不到自己的语言了。回退顺序 nativeName → name → id。
 */
function languageLabel(l) {
  return (l && (l.nativeName || l.name || l.id)) || ''
}

function switchLocale(id) {
  setLocale(id)
}

const settings = ref(null)
const busy = ref(false)
const about = ref({ version: '', repoUrl: '' })
/** 远端有新版时的检查结果；无更新或检查失败都保持 null */
const updateInfo = ref(null)

/** 写接口令牌：后端未启用保护时留空即可 */
const writeToken = ref(getWriteToken())
/** 已保存到 localStorage 的令牌，决定「写接口令牌」区块是否展示；
 *  不能直接用输入框的值，否则刚输入第一个字符区块就隐藏了 */
const savedWriteToken = ref(getWriteToken())
const writeTokenSaved = computed(() => !!savedWriteToken.value.trim())

function saveWriteToken() {
  persistWriteToken(writeToken.value)
  // 重新读一次，让 "当前" 提示跟着输入框的值走
  writeToken.value = getWriteToken()
  savedWriteToken.value = writeToken.value
  notify.success(writeToken.value ? t('令牌已保存到本机') : t('已清空令牌'))
  nextTick(updateActiveNav)
}

/** 目录项：computed 而非常量，否则语言切换后不更新 */
const baseNavItems = computed(() => [
  { id: 'language', label: t('界面语言') },
  { id: 'appearance', label: t('外观主题') },
  { id: 'features', label: t('功能开关') },
  { id: 'switch-mode', label: t('切换方式') },
  { id: 'canvas-calibrate', label: t('画面校准') },
  { id: 'idle-stop', label: t('空闲停止') },
  { id: 'cg-sound', label: t('CG 声音') },
  { id: 'rotation-toggle', label: t('轮换功能') },
  { id: 'media-default', label: t('游戏页展示') },
  { id: 'access', label: t('写接口令牌') },
  { id: 'about', label: t('关于') },
])
/**
 * 写接口令牌区块是否显示。
 *
 * 服务端已配置部署令牌（GALWORLD_DEPLOY_TOKEN / galworld.deploy.token）时
 * 不再显示：运维已经有一道统一入口，不该再让人填第二个令牌。
 * 例外是服务端确实启用了写接口保护（writeTokenConfigured）——那时不填
 * 令牌所有写操作都会 403，区块必须留着。
 * 本机填过令牌后同样隐藏，修改入口只保留在后台管理页。
 */
const showWriteToken = computed(() => !writeTokenSaved.value
  && (!about.value.deployTokenConfigured || about.value.writeTokenConfigured))
const navItems = computed(() =>
  baseNavItems.value.filter((n) => n.id !== 'access' || showWriteToken.value))
const activeNav = ref('switch-mode')

/** 仓库地址由后台管理配置；开关关闭时 /api/health 不下发该字段 */
const repoBase = computed(() => (about.value.repoUrl || '').replace(/\/+$/, ''))
/** 发布页随仓库地址走；仓库为内网 GitLab，用它的 releases 路径 */
const RELEASES_PAGE = computed(() => (repoBase.value ? `${repoBase.value}/-/releases` : ''))

/** 版本号归一化：去掉 Release tag 的 v 前缀与 SNAPSHOT 后缀 */
function normalizeVersion(v) {
  return String(v || '').trim().replace(/^v/i, '').replace(/-SNAPSHOT$/i, '')
}

/** 当前版本号直接链到对应 tag 页；SNAPSHOT 去掉后缀 */
const versionTagUrl = computed(() => {
  const v = normalizeVersion(about.value.version)
  return repoBase.value && /^\d/.test(v)
    ? `${repoBase.value}/-/tags/v${v}`
    : RELEASES_PAGE.value
})

/** 右侧目录定位到对应卡片 */
function gotoSection(id) {
  activeNav.value = id
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** 滚动时反查当前可视卡片，让左侧目录跟随高亮 */
let scrollRaf = 0
function updateActiveNav() {
  const offset = 60
  let current = navItems.value[0].id
  for (const n of navItems.value) {
    const el = document.getElementById(n.id)
    if (el && el.getBoundingClientRect().top <= offset) {
      current = n.id
    }
  }
  const doc = document.documentElement
  if (window.innerHeight + window.scrollY >= doc.scrollHeight - 4) {
    current = navItems.value[navItems.value.length - 1].id
  }
  activeNav.value = current
}

function onScroll() {
  if (scrollRaf) return
  scrollRaf = requestAnimationFrame(() => {
    scrollRaf = 0
    updateActiveNav()
  })
}

/** 当前展示的秒数单位：1=秒 60=分 3600=时 */
const coverUnit = ref(1)

async function load() {
  try {
    settings.value = await api.settings()
    try {
      about.value = await api.health()
    } catch {
      // 旧后端没有版本字段时不影响其它设置的加载
    }
  } catch (e) {
    notify.error(t("加载失败：{0}", e.message))
  }
}

/** 进入页面时检查远端版本；拉不到 Release（断网、限流）就静默跳过。
 *  必须用不广播维护事件的 quiet 版：否则 502 会被当成门户维护并强制刷新 */
async function checkUpdate() {
  const r = await api.checkUpdateQuiet()
  updateInfo.value = r.ok && r.data?.hasUpdate ? r.data : null
}

async function save(patch) {
  busy.value = true
  try {
    settings.value = await api.saveSettings(patch)
    applySettings(settings.value)
    notify.success(t('已保存'))
  } catch (e) {
    notify.error(t("保存失败：{0}", e.message))
  } finally {
    busy.value = false
  }
}

function setMode(mode) {
  save({ switchMode: mode })
}

function setCanvasMode(mode) {
  save({ canvasCalibrateMode: mode })
}

function setIdle(minutes) {
  save({ idleStopMinutes: String(minutes) })
}

function setCgSound(on) {
  save({ cgSound: on ? 'true' : 'false' })
}

function setRotationEnabled(on) {
  save({ rotationEnabled: on ? 'true' : 'false' })
}

/** 游戏页默认展示：cover=只显示封面 / cg=先封面，首个 CG 视频就绪后切换 */
function setDefaultMediaMode(mode) {
  save({ defaultMediaMode: mode })
}

/** kind: cover | cg */
function setRotationMode(kind, m) {
  save({ [kind + 'RotationMode']: m })
}

function setCgOrder(order) {
  save({ cgRotationOrder: order })
}

function switchUnit(kind, unit) {
  if (kind === 'cover') coverUnit.value = unit
}

function setRotationInterval(kind, value, unit) {
  const sec = Math.round(value * unit)
  if (sec < 1 || sec > 86400) {
    notify.warning(t('范围 1 秒 – 24 小时'))
    return
  }
  save({ [kind + 'RotationInterval']: String(sec) })
}

onMounted(() => {
  load().then(updateActiveNav)
  checkUpdate()
  window.addEventListener('scroll', onScroll, { passive: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  if (scrollRaf) cancelAnimationFrame(scrollRaf)
})
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <router-link to="/" class="ghost-btn">{{ t("← 游戏库") }}</router-link>
      <div class="spacer" />
      <div class="brand" style="font-size:15px;letter-spacing:.1em">{{ t("设置") }}</div>
    </header>

    <div v-if="!settings" class="loading">{{ t("加载中…") }}</div>

    <template v-else>
      <div class="settings-layout">
        <aside class="settings-nav">
          <button
            v-for="n in navItems"
            :key="n.id"
            :class="{ active: activeNav === n.id }"
            @click="gotoSection(n.id)"
          >{{ n.label }}</button>
        </aside>

        <div class="settings-content">
      <section id="language" class="panel">
        <div class="panel-head">
          <h2>{{ t("界面语言") }}</h2>
          <span class="muted-sm">{{ t("仅影响本机浏览器") }}</span>
        </div>
        <p class="muted-sm" style="margin-bottom:14px">
          {{ t("语言包由服务端提供：内置简体中文、繁體中文、English、日本語；把 JSON 放到服务器的 data/i18n 目录，或在后台管理页上传，即可新增语言，无需改代码。") }}
        </p>
        <div class="seg">
          <button
            v-for="l in languages"
            :key="l.id"
            :class="{ on: locale === l.id }"
            :disabled="i18nLoading"
            :title="l.id"
            @click="switchLocale(l.id)"
          >{{ languageLabel(l) }}</button>
        </div>
        <p v-if="loadError" class="muted-sm" style="margin-top:12px;color:var(--err)">
          {{ t("语言包加载失败：{0}", loadError) }}
        </p>
        <p class="muted-sm" style="margin-top:12px">
          {{ t("当前：{0}", currentName) }}
        </p>
      </section>

      <section id="appearance" class="panel">
        <div class="panel-head">
          <h2>{{ t("外观主题") }}</h2>
          <span class="muted-sm">{{ t("仅影响本机浏览器") }}</span>
        </div>
        <ThemePicker />
      </section>

      <section id="features" class="panel">
        <div class="panel-head">
          <h2>{{ t("功能开关") }}</h2>
          <span class="muted-sm">{{ t("停用只隐藏界面，数据保留") }}</span>
        </div>
        <p class="muted-sm" style="margin-bottom:14px">
          {{ t("关闭后，首页入口、列表筛选、游戏详情与编辑页都不再显示该维度； 已有关联数据不会被删除，重新开启即可恢复。") }}</p>
        <div class="rotation-block">
          <h3>{{ t("公司") }}</h3>
          <div class="seg">
            <button
              :class="{ on: settings.companyEnabled !== 'false' }"
              :disabled="busy"
              @click="save({ companyEnabled: 'true' })"
            >{{ t("启用") }}</button>
            <button
              :class="{ on: settings.companyEnabled === 'false' }"
              :disabled="busy"
              @click="save({ companyEnabled: 'false' })"
            >{{ t("停用") }}</button>
          </div>
        </div>
        <div class="rotation-block">
          <h3>{{ t("汉化组") }}</h3>
          <div class="seg">
            <button
              :class="{ on: settings.localizationGroupEnabled !== 'false' }"
              :disabled="busy"
              @click="save({ localizationGroupEnabled: 'true' })"
            >{{ t("启用") }}</button>
            <button
              :class="{ on: settings.localizationGroupEnabled === 'false' }"
              :disabled="busy"
              @click="save({ localizationGroupEnabled: 'false' })"
            >{{ t("停用") }}</button>
          </div>
        </div>
      </section>

      <section id="switch-mode" class="panel">
        <div class="panel-head">
          <h2>{{ t("切换游戏的方式") }}</h2>
        </div>
        <p class="muted-sm" style="margin-bottom:14px">
          {{ t("从游戏库点「进入游戏」时如何打开。无论哪种方式，**离开游玩页都不会中断游戏**。") }}</p>
        <div class="seg">
          <button
            :class="{ on: settings.switchMode === 'page' }"
            :disabled="busy"
            @click="setMode('page')"
          >{{ t("当前页跳转") }}</button>
          <button
            :class="{ on: settings.switchMode === 'tab' }"
            :disabled="busy"
            @click="setMode('tab')"
          >{{ t("新标签页") }}</button>
        </div>
        <p class="muted-sm" style="margin-top:12px">
          {{ t("当前：{0}", settings.switchMode === 'tab' ? t('新标签页（可并排多个游戏）') : t('当前页跳转（单窗口更简洁）')) }}</p>
      </section>

      <section id="canvas-calibrate" class="panel">
        <div class="panel-head">
          <h2>{{ t("画面校准") }}</h2>
        </div>
        <p class="muted-sm" style="margin-bottom:14px">
          {{ t("游戏窗口由游戏自身协商，画布比窗口大只会多出黑边，甚至让画面显得更小。 自动校准会在进入游玩页后测量窗口、调整画布并重启一次会话，最多 4 轮。") }}</p>
        <div class="seg">
          <button
            :class="{ on: settings.canvasCalibrateMode !== 'manual' }"
            :disabled="busy"
            @click="setCanvasMode('auto')"
          >{{ t("自动校准") }}</button>
          <button
            :class="{ on: settings.canvasCalibrateMode === 'manual' }"
            :disabled="busy"
            @click="setCanvasMode('manual')"
          >{{ t("手动校准") }}</button>
        </div>
        <p class="muted-sm" style="margin-top:12px">
          {{ t("当前：{0}", settings.canvasCalibrateMode === 'manual'
            ? t('手动——在游玩页右上角点「校准分辨率」')
            : t('自动——进入游戏后自动校准')) }}</p>
      </section>

      <section id="idle-stop" class="panel">
        <div class="panel-head">
          <h2>{{ t("空闲自动停止") }}</h2>
        </div>
        <p class="muted-sm" style="margin-bottom:14px">
          {{ t("离开游玩页超过设定时长后自动停止会话，省下编码资源。0 表示不自动停止。") }}</p>
        <div class="seg">
          <button
            v-for="m in [0, 3, 5, 10, 15, 20, 30, 60]"
            :key="m"
            :class="{ on: String(settings.idleStopMinutes) === String(m) }"
            :disabled="busy"
            @click="setIdle(m)"
          >{{ m === 0 ? t('不停止') : m + t(' 分钟') }}</button>
        </div>
        <p class="muted-sm" style="margin-top:12px">
          {{ t("当前：{0}", settings.idleStopMinutes === '0' ? t('不自动停止') : settings.idleStopMinutes + t(' 分钟')) }}</p>
      </section>

      <section id="cg-sound" class="panel">
        <div class="panel-head">
          <h2>{{ t("宣传 CG 声音") }}</h2>
        </div>
        <p class="muted-sm" style="margin-bottom:14px">
          {{ t("详情页播放宣传视频时是否默认带声音。播放器上始终有独立的静音按钮，可临时切换。") }}</p>
        <div class="seg">
          <button
            :class="{ on: settings.cgSound !== 'true' }"
            :disabled="busy"
            @click="setCgSound(false)"
          >{{ t("默认静音") }}</button>
          <button
            :class="{ on: settings.cgSound === 'true' }"
            :disabled="busy"
            @click="setCgSound(true)"
          >{{ t("默认有声") }}</button>
        </div>
        <p class="muted-sm" style="margin-top:12px">
          {{ t("当前：{0}", settings.cgSound === 'true' ? t('默认有声（可能和游戏声音重叠）') : t('默认静音')) }}</p>
      </section>

      <section id="rotation-toggle" class="panel">
        <div class="panel-head">
          <h2>{{ t("轮换功能") }}</h2>
        </div>
        <p class="muted-sm" style="margin-bottom:14px">
          {{ t("关闭后，库页卡片、推荐位与详情页只显示第一张封面 / 第一份宣传 CG，不再自动切换。") }}</p>
        <div class="seg">
          <button
            :class="{ on: settings.rotationEnabled !== 'false' }"
            :disabled="busy"
            @click="setRotationEnabled(true)"
          >{{ t("开启轮换") }}</button>
          <button
            :class="{ on: settings.rotationEnabled === 'false' }"
            :disabled="busy"
            @click="setRotationEnabled(false)"
          >{{ t("关闭轮换") }}</button>
        </div>
        <p class="muted-sm" style="margin-top:12px">
          {{ t("当前：{0}", settings.rotationEnabled === 'false' ? t('已关闭（固定首项）') : t('已开启')) }}</p>

        <div v-if="settings.rotationEnabled !== 'false'">
          <div class="section-title">{{ t("轮换规则（全局默认）") }}</div>
          <p class="muted-sm" style="margin-bottom:14px">
            {{ t("封面与宣传 CG 的切换规则各自独立。作用于未单独定制的游戏。") }}</p>

          <div class="rotation-block">
            <h3>{{ t("封面轮换") }}</h3>
            <div class="seg">
              <button
                :class="{ on: settings.coverRotationMode !== 'random' }"
                :disabled="busy"
                @click="setRotationMode('cover', 'seq')"
              >{{ t("顺序") }}</button>
              <button
                :class="{ on: settings.coverRotationMode === 'random' }"
                :disabled="busy"
                @click="setRotationMode('cover', 'random')"
              >{{ t("随机") }}</button>
            </div>
            <div class="backup-bar" style="margin-top:14px">
              <input
                class="input"
                type="number"
                min="1"
                :value="Math.round(parseInt(settings.coverRotationInterval || '6', 10) / coverUnit)"
                @change="setRotationInterval('cover', Number($event.target.value), coverUnit)"
              />
              <select class="input" style="width:100px" :value="String(coverUnit)" @change="switchUnit('cover', Number($event.target.value))">
                <option value="1">{{ t("秒") }}</option>
                <option value="60">{{ t("分") }}</option>
                <option value="3600">{{ t("时") }}</option>
              </select>
            </div>
            <p class="muted-sm" style="margin-top:10px">
              {{ t("当前：{0} · {1} 秒（1 秒 – 24 小时）", settings.coverRotationMode === 'random' ? t('随机') : t('顺序'), parseInt(settings.coverRotationInterval || '6', 10)) }}</p>
          </div>

          <div class="rotation-block">
            <h3>{{ t("宣传 CG 轮换") }}</h3>
            <div class="seg">
              <button
                :class="{ on: settings.cgRotationMode === 'autoNext' }"
                :disabled="busy"
                @click="setRotationMode('cg', 'autoNext')"
              >{{ t("播完自动下一条") }}</button>
              <button
                :class="{ on: settings.cgRotationMode === 'repeat' }"
                :disabled="busy"
                @click="setRotationMode('cg', 'repeat')"
              >{{ t("重复播放当前 CG") }}</button>
              <button
                :class="{ on: settings.cgRotationMode === 'stop' }"
                :disabled="busy"
                @click="setRotationMode('cg', 'stop')"
              >{{ t("播放结束自动停") }}</button>
            </div>
            <div v-if="settings.cgRotationMode === 'autoNext'" class="rotation-sub">
              <h3>{{ t("顺序") }}</h3>
              <div class="seg">
                <button
                  :class="{ on: settings.cgRotationOrder !== 'random' }"
                  :disabled="busy"
                  @click="setCgOrder('seq')"
                >{{ t("顺序") }}</button>
                <button
                  :class="{ on: settings.cgRotationOrder === 'random' }"
                  :disabled="busy"
                  @click="setCgOrder('random')"
                >{{ t("随机") }}</button>
              </div>
            </div>
            <p class="muted-sm" style="margin-top:10px">
              {{ t("当前：{0}", settings.cgRotationMode === 'repeat' ? t('重复播放当前 CG')
                : settings.cgRotationMode === 'stop' ? t('播放结束自动停')
                : t('播完自动下一条 · ') + (settings.cgRotationOrder === 'random' ? t('随机') : t('顺序'))) }}</p>
            <p class="muted-sm" style="margin-top:6px">
              {{ t("「播完自动下一条」：视频按播放结束切下一项，图片 CG 使用固定展示时长。 「重复播放当前 CG」：视频循环播放当前项。「播放结束自动停」：播完停在最后一帧。") }}</p>
          </div>
        </div>
      </section>

      <section id="media-default" class="panel">
        <div class="panel-head">
          <h2>{{ t("游戏页展示") }}</h2>
        </div>
        <p class="muted-sm" style="margin-bottom:14px">
          {{ t("游戏详情页右上角大屏默认展示什么。在游戏页手动切换过的游戏以游戏内选择为准，其余跟随这里的默认值。") }}</p>
        <div class="seg">
          <button
            :class="{ on: settings.defaultMediaMode !== 'cg' }"
            :disabled="busy"
            @click="setDefaultMediaMode('cover')"
          >{{ t("封面") }}</button>
          <button
            :class="{ on: settings.defaultMediaMode === 'cg' }"
            :disabled="busy"
            @click="setDefaultMediaMode('cg')"
          >CG</button>
        </div>
        <p class="muted-sm" style="margin-top:12px">
          {{ t("当前：{0}", settings.defaultMediaMode === 'cg'
            ? t('优先 CG（先显示封面，CG 加载完成后自动切换）')
            : t('只显示封面')) }}</p>
        <p class="muted-sm" style="margin-top:6px">
          {{ t("选择「优先 CG」时：先显示封面，CG 加载完成后自动切到 CG，右上角按钮同步高亮； 没有上传 CG 的游戏仍显示封面；CG 只有图片时，图片加载完成后同样展示 CG。") }}</p>
        <p class="muted-sm" style="margin-top:6px">
          {{ t("CG 尚未加载完成就手动点「CG」时，内容区会显示加载提示与进度，就绪后自动展示；全部加载失败则回退封面。") }}</p>

        <div class="section-title">{{ t("首页推荐位") }}</div>
        <div class="seg">
          <button
            :class="{ on: settings.homeHeroSize !== 'off' }"
            :disabled="busy"
            @click="save({ homeHeroSize: 'medium' })"
          >{{ t("显示大图") }}</button>
          <button
            :class="{ on: settings.homeHeroSize === 'off' }"
            :disabled="busy"
            @click="save({ homeHeroSize: 'off' })"
          >{{ t("只留横条") }}</button>
        </div>
        <p class="muted-sm" style="margin-top:10px">
          {{ t("首页固定推荐 10 款，推荐位高度不再随游戏数量变化。") }}</p>
      </section>

      <section v-if="showWriteToken" id="access" class="panel">
        <div class="panel-head">
          <h2>{{ t("写接口令牌") }}</h2>
          <span class="muted-sm">{{ t("仅本机浏览器") }}</span>
        </div>
        <p class="muted-sm" style="margin-bottom:14px">
          {{ t("门户默认不校验写操作。如果服务端配置了") }}<code>GALWORLD_WRITE_TOKEN</code>
          {{ t("（或") }}<code>galworld.write.token</code>{{ t("），启动游戏、上传媒体、修改设置都会被拒绝（403）， 需要在这里填入同一个令牌。这里只保存到本机 localStorage，不会修改服务端配置。 填写后本区块会隐藏，之后如需修改请到「后台管理」页。") }}</p>
        <p class="muted-sm" style="margin-bottom:14px">
          {{ t("服务端当前：{0}", about.writeTokenConfigured ? t('已启用写接口保护') : t('未启用写接口保护')) }}</p>
        <div class="deploy-token-row">
          <input
            v-model="writeToken"
            class="input"
            type="password"
            :placeholder="t('写接口令牌（未启用保护时留空）')"
            @keyup.enter="saveWriteToken"
          />
          <button class="btn" @click="saveWriteToken">{{ t("保存") }}</button>
        </div>
        <p class="muted-sm" style="margin-top:10px">
          {{ t("当前：{0}", writeTokenSaved ? t('已填写（保存在本机 localStorage）') : t('未填写')) }}</p>
      </section>

      <section id="about" class="panel">
        <div class="panel-head">
          <h2>{{ t("关于") }}</h2>
        </div>
        <div class="setting-row">
          <span>{{ t("版本") }}</span>
          <span class="version-line">
            <a v-if="versionTagUrl" :href="versionTagUrl" target="_blank" rel="noopener" class="mono meta-link">
              {{ about.version || 'dev' }}
            </a>
            <span v-else class="mono">{{ about.version || 'dev' }}</span>
            <span
              v-if="updateInfo"
              class="version-update mono"
              :title="t('到后台管理进行更新处理')"
            >{{ normalizeVersion(updateInfo.currentVersion || about.version) }} -&gt; {{ normalizeVersion(updateInfo.latestVersion) }}</span>
          </span>
        </div>
        <div class="setting-row">
          <span>{{ t("代码仓库") }}</span>
          <a v-if="repoBase" :href="repoBase" target="_blank" rel="noopener" class="meta-link">
            {{ repoBase }}
          </a>
          <span v-else class="muted-sm">{{ t("未公开") }}</span>
        </div>
        <div class="setting-row">
          <span>{{ t("发布版本") }}</span>
          <a v-if="RELEASES_PAGE" :href="RELEASES_PAGE" target="_blank" rel="noopener" class="meta-link">
            {{ RELEASES_PAGE }}
          </a>
          <span v-else class="muted-sm">{{ t("未公开") }}</span>
        </div>
      </section>
        </div>
      </div>
    </template>
  </div>
</template>
