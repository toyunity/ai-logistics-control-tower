package com.example.controltower;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

// 실제 Claude와 Gemini를 호출해 비용이 든다. 키가 둘 다 있을 때만 돈다.
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@Timeout(180)
class LiveApiIT extends RealModelChecks {}
