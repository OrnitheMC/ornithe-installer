package org.quiltmc.installer;

import java.io.*;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MmcPackCreator {
    public static String transformPackJson(String examplePackJson, String gameVersion, LoaderType type, String loaderVersion, String lwjglVersion){
        return examplePackJson
                .replaceAll("\\$\\{mc_version}", gameVersion)
                .replaceAll("\\$\\{loader_version}", loaderVersion)
                .replaceAll("\\$\\{loader_name}", type.getFancyName() + " Loader")
                .replaceAll(
                        "\\$\\{loader_uid}",
                        type == LoaderType.FABRIC
                                ? "net.fabricmc.fabric-loader"
                                : "org.quiltmc.quilt-loader"
                ).replaceAll("\\$\\{lwjgl_version}", lwjglVersion)
                .replaceAll("\\$\\{lwjgl_major_ver}", lwjglVersion.substring(0,1));
    }

    public static void compileMmcZip(File outPutDir,String gameVersion, LoaderType type, String loaderVersion, String lwjglVersion){
        File examplePackJson = new File("src/main/resources/packformat/mmc-pack.json");
        File exampleIntermediaryJson = new File("src/main/resources/packformat/patches/net.fabricmc.intermediary.json");
        File exampleInstanceCfg = new File("src/main/resources/packformat/instance.cfg");
        File icon = new File("src/main/resources/packformat/ornithe.png");

        try {
            String transformedPackJson = transformPackJson(
                    Files.readString(examplePackJson.toPath()), gameVersion, type, loaderVersion, lwjglVersion
            );
            String transformedIntermediaryJson = Files.readString(exampleIntermediaryJson.toPath())
                    .replaceAll("\\$\\{mc_version}", gameVersion);
            String transformedInstanceCfg = Files.readString(exampleInstanceCfg.toPath())
                    .replaceAll("\\$\\{mc_version}", gameVersion);

            File tempDir = new File(outPutDir, ".temp");
            if (!tempDir.exists()) {
                tempDir.mkdir();
            }

            File packJson = new File(tempDir, "mmc-pack.json");
            packJson.createNewFile();

            File instanceCfg = new File(tempDir,"instance.cfg");
            instanceCfg.createNewFile();

            File patchesDir = new File(tempDir,"patches");
            if (!patchesDir.exists()) {
                patchesDir.mkdir();
            }

            File intermediaryJson = new File(patchesDir, "net.fabricmc.intermediary.json");
            intermediaryJson.createNewFile();

            writeToFile(packJson, transformedPackJson);
            writeToFile(intermediaryJson, transformedIntermediaryJson);
            writeToFile(instanceCfg, transformedInstanceCfg);

            File zipFile = new File(outPutDir,"ornithe.zip");
            if (!zipFile.exists()) {
                zipFile.createNewFile();
            }

            FileOutputStream fileOut = new FileOutputStream(zipFile);
            ZipOutputStream zipOut = new ZipOutputStream(fileOut);

            addFileToZip(zipOut, icon, icon.getName());
            addFileToZip(zipOut, instanceCfg, instanceCfg.getName());
            addFileToZip(zipOut, packJson, packJson.getName());

            zipOut.putNextEntry(new ZipEntry(patchesDir.getName() + "/"));
            zipOut.closeEntry();

            addFileToZip(zipOut, intermediaryJson, patchesDir.getName() + "/" + intermediaryJson.getName());

            zipOut.close();
            fileOut.close();

            intermediaryJson.delete();
            patchesDir.delete();
            packJson.delete();
            instanceCfg.delete();
            tempDir.delete();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeToFile(File fileToBeWritten, String writing) throws IOException {
        FileWriter fileWriter = new FileWriter(fileToBeWritten);
        fileWriter.write(writing);
        fileWriter.close();
    }

   public static void addFileToZip(ZipOutputStream zipOut, File fileToZip, String fileName) throws IOException {
       FileInputStream fis = new FileInputStream(fileToZip);
       ZipEntry zipEntry = new ZipEntry(fileName);
       zipOut.putNextEntry(zipEntry);
       byte[] bytes = new byte[1024];
       int length;
       while ((length = fis.read(bytes)) >= 0) {
           zipOut.write(bytes, 0, length);
       }
       fis.close();
   }
}