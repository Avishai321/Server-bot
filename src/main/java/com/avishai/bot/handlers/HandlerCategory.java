package com.avishai.bot.handlers;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum HandlerCategory {
    STORAGE_AND_MEDIA("📁 Storage & Media"),
    INFRASTRUCTURE("🐳 Infrastructure"),
    MONITORING_AND_ADMIN("⚙️ Monitoring & Admin"),
    OTHER("🔧 Other");

    private final String displayName;
}
