package com.julz.screenshottoolkit.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShotMetadataTest {
    @Test
    void teleportCommandIncludesDimensionAndRotation() {
        ShotMetadata metadata = new ShotMetadata();
        metadata.dimension = "minecraft:the_nether";
        metadata.x = 10.5;
        metadata.y = 64.0;
        metadata.z = -3.25;
        metadata.yaw = 90f;
        metadata.pitch = -12.5f;
        assertEquals("/execute in minecraft:the_nether run tp @s 10.50 64.00 -3.25 90.0 -12.5", metadata.teleportCommand());
    }

    @Test
    void teleportCommandWithoutDimensionOrRotation() {
        ShotMetadata metadata = new ShotMetadata();
        metadata.x = 1.0;
        metadata.y = 2.0;
        metadata.z = 3.0;
        assertEquals("/tp @s 1.00 2.00 3.00", metadata.teleportCommand());
    }

    @Test
    void noPositionNoCommand() {
        assertNull(new ShotMetadata().teleportCommand());
    }

    @Test
    void dimensionNames() {
        ShotMetadata metadata = new ShotMetadata();
        assertNull(metadata.dimensionName());
        metadata.dimension = "minecraft:the_end";
        assertEquals("The End", metadata.dimensionName());
        metadata.dimension = "mymod:deep_dark_realm";
        assertEquals("Deep dark realm", metadata.dimensionName());
    }
}
