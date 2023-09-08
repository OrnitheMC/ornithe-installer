package org.quiltmc.installer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MmcPackCreator {
    public static String transformPackJson(String examplePackJson, VersionManifest.Version gameVersion, LoaderType type, String loaderVersion){
        return examplePackJson
                .replaceAll("\\$\\{mc_version}", gameVersion.id())
                .replaceAll("\\$\\{loader_version}", loaderVersion)
                .replaceAll("\\$\\{loader_name}", type.getFancyName() + " Loader")
                .replaceAll(
                        "\\$\\{loader_uid}",
                        type == LoaderType.FABRIC
                                ? "net.fabricmc.fabric-loader"
                                : "org.quiltmc.quilt-loader"
                );
    }

    public static void compileMmcZip(File outPutDir,VersionManifest.Version gameVersion, LoaderType type, String loaderVersion){
        File examplePackJson = new File("src/main/resources/packformat/mmc-pack.json");
        File exampleIntermediaryJson = new File("src/main/resources/packformat/patches/net.fabricmc.intermediary.json");

        try {
            String transformedPackJson = transformPackJson(
                    Files.readString(examplePackJson.toPath()), gameVersion, type, loaderVersion
            );
            String transformedIntermediaryJson = Files.readString(exampleIntermediaryJson.toPath())
                    .replaceAll("\\$\\{mc_version}", gameVersion.id());

            File tempDir = new File(outPutDir, ".temp");
            if (!tempDir.exists()) {
                tempDir.mkdir();
            }

            File packJson = new File(tempDir, "mmc-pack.json");
            packJson.createNewFile();

            File patchesDir = new File(tempDir,"patches");
            if (!patchesDir.exists()) {
                patchesDir.mkdir();
            }

            File intermediaryJson = new File(patchesDir, "net.fabricmc.intermediary.json");
            intermediaryJson.createNewFile();

            FileWriter packFileWriter = new FileWriter(packJson);
            packFileWriter.write(transformedPackJson);
            packFileWriter.close();

            FileWriter intermediaryFileWriter = new FileWriter(intermediaryJson);
            intermediaryFileWriter.write(transformedIntermediaryJson);
            intermediaryFileWriter.close();



        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


}
