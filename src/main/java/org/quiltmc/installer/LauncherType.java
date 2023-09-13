package org.quiltmc.installer;

public enum LauncherType {
    VANILLA("Vanilla Launcher"),
    MULTIMC("MultiMc/PrismLauncher");


    private final String name;

    private LauncherType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static LauncherType of(String name) {
        String nameToCheck = name;
        
        if (name.contains(" ")) {
            nameToCheck = name.split(" ")[0].toUpperCase();
        } else if (name.contains("/")) {
            nameToCheck = name.split("/")[0].toUpperCase();
        }

        return valueOf(nameToCheck);
    }
}
