# PDP-10 Emulator ProGuard Rules

# Keep CPU, memory and assembler classes (used via reflection potentially)
-keep class com.pdp10.emulator.cpu.** { *; }
-keep class com.pdp10.emulator.memory.** { *; }
-keep class com.pdp10.emulator.assembler.** { *; }

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**
