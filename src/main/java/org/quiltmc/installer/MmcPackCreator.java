/*
 * Copyright 2021 QuiltMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.quiltmc.installer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MmcPackCreator {
	public static String transformPackJson(String examplePackJson, String gameVersion, LoaderType type, String loaderVersion, String lwjglVersion){
		String lwjglMajorVer = lwjglVersion.substring(0,1);
		return examplePackJson
				.replaceAll("\\$\\{mc_version}", gameVersion)
				.replaceAll("\\$\\{loader_version}", loaderVersion)
				.replaceAll("\\$\\{loader_name}", type.getFancyName() + " Loader")
				.replaceAll("\\$\\{loader_uid}", type.getMavenUid())
				.replaceAll("\\$\\{lwjgl_version}", lwjglVersion)
				.replaceAll("\\$\\{lwjgl_major_ver}", lwjglMajorVer)
				.replaceAll("\\$\\{lwjgl_uid}", lwjglMajorVer.equals("3") ? "org.lwjgl3" : "org.lwjgl");
	}

	public static void compileMmcZip(File outPutDir,String gameVersion, LoaderType type, String loaderVersion, String lwjglVersion){
		String examplePackDir = "packformat";
		String packJsonPath = "mmc-pack.json";
		String intermediaryJsonPath = "patches/net.fabricmc.intermediary.json";
		String instanceCfgPath = "instance.cfg";
		String iconPath = "ornithe.png";

		try {
			String transformedPackJson = transformPackJson(
					readResource(examplePackDir, packJsonPath), gameVersion, type, loaderVersion, lwjglVersion
			);
			String transformedIntermediaryJson = readResource(examplePackDir, intermediaryJsonPath)
					.replaceAll("\\$\\{mc_version}", gameVersion);
			String transformedInstanceCfg = readResource(examplePackDir, instanceCfgPath)
					.replaceAll("\\$\\{mc_version}", gameVersion);

			File zipFile = new File(outPutDir,"Ornithe-" + gameVersion + ".zip");
			if (zipFile.exists()) {
				zipFile.delete();
			}

			FileOutputStream fileOut = new FileOutputStream(zipFile);
			ZipOutputStream zipOut = new ZipOutputStream(fileOut);

			copyResourceToZip(zipOut, examplePackDir, iconPath);
			writeJsonToZip(zipOut, instanceCfgPath, transformedInstanceCfg);
			writeJsonToZip(zipOut, intermediaryJsonPath, transformedIntermediaryJson);
			writeJsonToZip(zipOut, packJsonPath, transformedPackJson);

			zipOut.close();
			fileOut.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static String readResource(String dir, String path) throws IOException {
		InputStream resource = MmcPackCreator.class.getResourceAsStream(String.format("%s/%s", dir, path));
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
		for (int length; (length = resource.read(buffer)) != -1; ) {
			os.write(buffer, 0, length);
		}
		return os.toString(StandardCharsets.UTF_8);
	}

	private static void copyResourceToZip(ZipOutputStream zipOut, String dir, String path) throws IOException {
		InputStream resource = MmcPackCreator.class.getResourceAsStream(String.format("%s/%s", dir, path));
		byte[] buffer = new byte[1024];
		ZipEntry zipEntry = new ZipEntry(path);
		zipOut.putNextEntry(zipEntry);
		for (int length; (length = resource.read(buffer)) != -1; ) {
			zipOut.write(buffer, 0, length);
		}
	}

	private static void writeJsonToZip(ZipOutputStream zipOut, String path, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		ZipEntry zipEntry = new ZipEntry(path);
		zipOut.putNextEntry(zipEntry);
		zipOut.write(bytes);
	}
}
