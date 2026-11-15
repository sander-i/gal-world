/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 sander
 */

package com.galworld;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class GalWorldApplication implements ApplicationRunner {

    private final com.galworld.service.PlaytimeService playtime;

    public GalWorldApplication(com.galworld.service.PlaytimeService playtime) {
        this.playtime = playtime;
    }

    public static void main(String[] args) throws IOException {
        String root = System.getenv().getOrDefault("GALWORLD_ROOT",
                Path.of(System.getProperty("user.home"), "galworld").toString());
        // H2 不会自动创建父目录，启动前先建好
        Files.createDirectories(Path.of(root, "data", "portal"));
        Files.createDirectories(Path.of(root, "games"));
        SpringApplication.run(GalWorldApplication.class, args);
    }

    /** 门户重启时结算上次遗留的进行中记录，避免时长一直虚增 */
    @Override
    public void run(ApplicationArguments args) {
        playtime.closeAllOpen();
    }
}
