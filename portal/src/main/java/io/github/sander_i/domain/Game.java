/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package io.github.sander_i.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 游戏注册表。
 *
 * 运行时参数（exe、端口、显示号等）的唯一真相是磁盘上的
 * {@code ~/galworld/games/<id>/game.env}，本实体是它在本地的投影，
 * 由 {@code GameScanService} 从文件同步。额外字段（简介、封面）只存在于这里。
 */
@Entity
@Table(name = "game")
public class Game {

    /** 稳定标识，与 game.env 的 GAME_ID、目录名一致 */
    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    private String exe;
    /** 备用启动程序（如中文启动器）；exe 是当前生效项，null 表示没有备用 */
    private String exeAlt;
    private String workDir;
    private String winePrefix;

    /**
     * 游戏进程的 LANG，写进 game.env 的 GAME_LANG。
     *
     * 中文汉化补丁多用 ANSI/CP936 存文本，需要 zh_CN.UTF-8 才能正确转换；
     * 日文原版 / UTF-8 游戏留空走默认 C.UTF-8（改成 936 会让 Shift-JIS 文本错乱）。
     */
    @Column(name = "game_lang", length = 32)
    private String gameLang;

    private int width = 1280;
    private int height = 720;
    private int displayNum;
    private int port;
    private int loopbackSlot;
    /**
     * 音频 loopback 卡号（snd-aloop 的第几张卡）。
     *
     * 单卡只有 8 个 subdevice，超过 8 个游戏要靠多装几张卡扩容；
     * 每张卡内部再按 {@link #loopbackSlot} 区分通道。
     *
     * columnDefinition 带 default 是必需的：hibernate 的 ddl-auto=update 会发
     * `alter table game add column loopback_card integer not null`，而表里已有
     * 数据时 H2 直接拒绝（NULL not allowed），整个列加不上，随后所有读 game
     * 的查询都报 42S22 列不存在。带 default 0 才能让老行自动取默认值。
     */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int loopbackCard;
    private String framerate = "60,30-60";

    /**
     * 画质参数，写进 game.env 的 {@code VIDEO_CRF} / {@code VIDEO_PAINTOVER_CRF}
     * / {@code RATE_CONTROL_MODE} / {@code VIDEO_BITRATE} / {@code KEYFRAME_INTERVAL}。
     *
     * 全部可空，{@code null} 表示「门户不管理这个键」——{@code GameScanService.writeEnv}
     * 会保留 game.env 里人工写的值。这样老游戏的手工调参不会被一次门户保存冲掉，
     * 而页面上填了就由门户接管。用 Integer 而不是 int 就是为了区分「没填」与「填了 0」。
     *
     * 取值范围与 Selkies settings.py 对齐：CRF 5–50、paintover CRF 5–50、
     * keyframe interval 0–300 秒。
     */
    private Integer videoCrf;
    private Integer videoPaintoverCrf;
    /** crf（恒定质量）或 cbr（恒定码率）；null = 保留 game.env 的值 */
    private String rateControlMode;
    /** CBR 模式下的码率串，格式见 Selkies：{@code 40000,5000-80000} */
    private String videoBitrate;
    /** 定时 IDR 间隔，单位**秒**；0 = 无限 GOP（Selkies 默认） */
    private Integer keyframeInterval;

    @Column(length = 2000)
    private String description;
    private String coverPath;
    private String iconPath;
    /** 当前图标是否由公司官网自动抓取（Boolean 允许老库迁移时为空） */
    private Boolean iconAutoFetched;
    /** 用户删除图标后置 true，之后不再自动抓取 */
    private Boolean iconAutoFetchDisabled;
    /** 宣传 CG（图片或视频），仅用于页面展示，不参与游戏链路 */
    @Column(length = 500)
    private String cgPath;
    /** 高码率版本（可选），播放器按网络与设备择优 */
    @Column(length = 500)
    private String cgHighPath;
    /** 低码率版本（可选），网络差或省流时使用 */
    @Column(length = 500)
    private String cgLowPath;

    /** 轮换方式覆盖：null 表示跟随全局；seq / random */
    @Column(name = "rotation_mode", length = 16)
    private String rotationMode;
    /** 轮换间隔覆盖（秒）：null 表示跟随全局 */
    @Column(name = "rotation_interval")
    private Integer rotationInterval;

    /** 封面轮换覆盖：null 表示跟随全局 */
    @Column(name = "cover_rotation_mode", length = 16)
    private String coverRotationMode;
    @Column(name = "cover_rotation_interval")
    private Integer coverRotationInterval;

    /** 宣传 CG 一级行为覆盖：autoNext / repeat / stop；null 表示跟随全局 */
    @Column(name = "cg_rotation_mode", length = 16)
    private String cgRotationMode;
    /** 宣传 CG 二级顺序覆盖：seq / random；null 表示跟随全局 */
    @Column(name = "cg_rotation_order", length = 16)
    private String cgRotationOrder;
    @Column(name = "cg_rotation_interval")
    private Integer cgRotationInterval;

    /** 详情页大屏默认展示：cg / cover；null 视为 cg。跨浏览器共用的游戏级配置 */
    @Column(name = "media_mode", length = 8)
    private String mediaMode;

    /** 用户手动指定的存档目录名（相对游戏目录），逗号分隔；留空则用默认约定 */
    @Column(name = "save_dirs", length = 500)
    private String saveDirs;

    private Instant createdAt;
    private Instant updatedAt;

    /** 文件系统里是否存在对应目录与 env（不持久化） */
    @jakarta.persistence.Transient
    private boolean onDisk;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getExe() { return exe; }
    public void setExe(String exe) { this.exe = exe; }

    public String getExeAlt() { return exeAlt; }
    public void setExeAlt(String exeAlt) { this.exeAlt = exeAlt; }

    public String getWorkDir() { return workDir; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }

    public String getWinePrefix() { return winePrefix; }
    public void setWinePrefix(String winePrefix) { this.winePrefix = winePrefix; }

    public String getGameLang() { return gameLang; }
    public void setGameLang(String gameLang) { this.gameLang = gameLang; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public int getDisplayNum() { return displayNum; }
    public void setDisplayNum(int displayNum) { this.displayNum = displayNum; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public int getLoopbackSlot() { return loopbackSlot; }
    public void setLoopbackSlot(int loopbackSlot) { this.loopbackSlot = loopbackSlot; }

    public int getLoopbackCard() { return loopbackCard; }
    public void setLoopbackCard(int loopbackCard) { this.loopbackCard = loopbackCard; }

    public String getFramerate() { return framerate; }
    public void setFramerate(String framerate) { this.framerate = framerate; }

    public Integer getVideoCrf() { return videoCrf; }
    public void setVideoCrf(Integer videoCrf) { this.videoCrf = videoCrf; }

    public Integer getVideoPaintoverCrf() { return videoPaintoverCrf; }
    public void setVideoPaintoverCrf(Integer videoPaintoverCrf) { this.videoPaintoverCrf = videoPaintoverCrf; }

    public String getRateControlMode() { return rateControlMode; }
    public void setRateControlMode(String rateControlMode) { this.rateControlMode = rateControlMode; }

    public String getVideoBitrate() { return videoBitrate; }
    public void setVideoBitrate(String videoBitrate) { this.videoBitrate = videoBitrate; }

    public Integer getKeyframeInterval() { return keyframeInterval; }
    public void setKeyframeInterval(Integer keyframeInterval) { this.keyframeInterval = keyframeInterval; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCoverPath() { return coverPath; }
    public void setCoverPath(String coverPath) { this.coverPath = coverPath; }

    public String getIconPath() { return iconPath; }
    public void setIconPath(String iconPath) { this.iconPath = iconPath; }

    public boolean isIconAutoFetched() { return Boolean.TRUE.equals(iconAutoFetched); }
    public void setIconAutoFetched(boolean iconAutoFetched) { this.iconAutoFetched = iconAutoFetched; }

    public boolean isIconAutoFetchDisabled() { return Boolean.TRUE.equals(iconAutoFetchDisabled); }
    public void setIconAutoFetchDisabled(boolean iconAutoFetchDisabled) {
        this.iconAutoFetchDisabled = iconAutoFetchDisabled;
    }

    public String getCgPath() { return cgPath; }
    public void setCgPath(String cgPath) { this.cgPath = cgPath; }

    public String getCgHighPath() { return cgHighPath; }
    public void setCgHighPath(String cgHighPath) { this.cgHighPath = cgHighPath; }

    public String getCgLowPath() { return cgLowPath; }
    public void setCgLowPath(String cgLowPath) { this.cgLowPath = cgLowPath; }

    public String getRotationMode() { return rotationMode; }
    public void setRotationMode(String rotationMode) { this.rotationMode = rotationMode; }

    public Integer getRotationInterval() { return rotationInterval; }
    public void setRotationInterval(Integer rotationInterval) { this.rotationInterval = rotationInterval; }

    public String getCoverRotationMode() { return coverRotationMode; }
    public void setCoverRotationMode(String coverRotationMode) {
        this.coverRotationMode = coverRotationMode;
    }

    public Integer getCoverRotationInterval() { return coverRotationInterval; }
    public void setCoverRotationInterval(Integer coverRotationInterval) {
        this.coverRotationInterval = coverRotationInterval;
    }

    public String getCgRotationMode() { return cgRotationMode; }
    public void setCgRotationMode(String cgRotationMode) { this.cgRotationMode = cgRotationMode; }

    public String getCgRotationOrder() { return cgRotationOrder; }
    public void setCgRotationOrder(String cgRotationOrder) {
        this.cgRotationOrder = cgRotationOrder;
    }

    public Integer getCgRotationInterval() { return cgRotationInterval; }
    public void setCgRotationInterval(Integer cgRotationInterval) {
        this.cgRotationInterval = cgRotationInterval;
    }

    public String getMediaMode() { return mediaMode; }
    public void setMediaMode(String mediaMode) { this.mediaMode = mediaMode; }

    public String getSaveDirs() { return saveDirs; }
    public void setSaveDirs(String saveDirs) { this.saveDirs = saveDirs; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isOnDisk() { return onDisk; }
    public void setOnDisk(boolean onDisk) { this.onDisk = onDisk; }
}
