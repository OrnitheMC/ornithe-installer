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
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.quiltmc.parsers.json.JsonReader;
import org.quiltmc.parsers.json.JsonToken;
import com.vdurmont.semver4j.*;

public class MmcPackCreator {
	private static final String ENV_WRAPPER_COMMAND = "WrapperCommand=\"env __GL_THREADED_OPTIMIZATIONS=0\"";
	private static final boolean IS_LINUX_LIKE_OS;
	private static final Semver VERSION_1_6 = new Semver("1.6.0-pre+06251516");
	private static final Semver VERSION_1_3 = new Semver("1.3.0-pre+07261249");

	private static String findLwjglVersion(VersionManifest manifest, String gameVersion) {
		for (String rawUrl : manifest.getVersion(gameVersion).details().manifests()) {
			try {
				URL url = new URL(rawUrl);
				URLConnection connection = Connections.openConnection(url);

				try (JsonReader reader = JsonReader.json(new BufferedReader(new InputStreamReader(connection.getInputStream())))) {
					String lwjglVersion = findLwjglVersion(reader);

					if (lwjglVersion != null) {
						return lwjglVersion;
					}
				}
			} catch (IOException e) {
				throw new RuntimeException("issue while finding lwjgl version for Minecraft " + gameVersion, e);
			}
		}

		throw new RuntimeException("unable to find lwjgl version for Minecraft " + gameVersion);
	}

	private static String findLwjglVersion(JsonReader reader) throws IOException, ParseException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseException("Version Manifest was invalid type", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			switch (reader.nextName()) {
			case "libraries":
				if (reader.peek() != JsonToken.BEGIN_ARRAY) {
					throw new ParseException("libraries must be an array", reader);
				}

				reader.beginArray();

				while (reader.hasNext()) {
					if (reader.peek() != JsonToken.BEGIN_OBJECT) {
						throw new ParseException("library entries must all be objects", reader);
					}

					reader.beginObject();

					while (reader.hasNext()) {
						switch (reader.nextName()) {
						case "name":
							if (reader.peek() != JsonToken.STRING) {
								throw new ParseException("library name must be a string", reader);
							}

							String name = reader.nextString();
							String[] maven = name.split("[:]");
							String artifact = maven[1];
							String version = maven[2];

							if (artifact.equals("lwjgl")) {
								return version;
							}

							break;
						default:
							reader.skipValue();
						}
					}

					reader.endObject();
				}

				reader.endArray();

				break;
			default:
				reader.skipValue();
			}
		}

		reader.endObject();

		return null;
	}

	private static String transformPackJson(String examplePackJson, String gameVersion, LoaderType type, String loaderVersion, String lwjglVersion, String intermediaryVersion){
		String lwjglMajorVer = lwjglVersion.substring(0,1);
		return examplePackJson
				.replaceAll("\\$\\{mc_version}", gameVersion)
				.replaceAll("\\$\\{intermediary_ver}", intermediaryVersion)
				.replaceAll("\\$\\{loader_version}", loaderVersion)
				.replaceAll("\\$\\{loader_name}", type.getLocalizedName() + " Loader")
				.replaceAll("\\$\\{loader_uid}", type.getMavenUid())
				.replaceAll("\\$\\{lwjgl_version}", lwjglVersion)
				.replaceAll("\\$\\{lwjgl_major_ver}", lwjglMajorVer)
				.replaceAll("\\$\\{lwjgl_uid}", lwjglMajorVer.equals("3") ? "org.lwjgl3" : "org.lwjgl");
	}

	public static void compileMmcZip(File outPutDir,String gameVersion, LoaderType loaderType, String loaderVersion, VersionManifest manifest){
		String examplePackDir = "/packformat";
		String packJsonPath = "mmc-pack.json";
		String intermediaryJsonPath = "patches/net.fabricmc.intermediary.json";
		String instanceCfgPath = "instance.cfg";
		String iconPath = "ornithe.png";

		String noAppletTrait = ""; // this is left empty and only filled in if the version is correct
		String intermediaryVersion = gameVersion;

		VersionManifest.VersionDetails details = manifest.getVersion(gameVersion).details();
		String normalizedVersion = details.normalizedVersion();

		Semver semver = new Semver(normalizedVersion);
		if(semver.isLowerThan(VERSION_1_6)){
			if(semver.isLowerThan(VERSION_1_3)){
				intermediaryVersion+= "-client";
			}
			noAppletTrait = " \"+traits\": [\n" +
					"    \t\"noapplet\"\n" +
					"    ],";
		}

		try {
			String transformedPackJson = transformPackJson(
					readResource(examplePackDir, packJsonPath), gameVersion, loaderType, loaderVersion, findLwjglVersion(manifest, gameVersion), intermediaryVersion
			);
			String transformedIntermediaryJson = readResource(examplePackDir, intermediaryJsonPath)
					.replaceAll("\\$\\{mc_version}", gameVersion)
					.replaceAll("\\$\\{intermediary_ver}", intermediaryVersion)
					.replaceAll("\\$\\{noapplet}", noAppletTrait);

			String transformedInstanceCfg = readResource(examplePackDir, instanceCfgPath)
					.replaceAll("\\$\\{mc_version}", gameVersion);

			if(IS_LINUX_LIKE_OS){
				transformedInstanceCfg+= "\n" +"OverrideCommands=true" +"\n" + ENV_WRAPPER_COMMAND;
			}

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

	static {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		IS_LINUX_LIKE_OS = os.contains("linux") || !(os.contains("win") || os.contains("mac"));
	}
}
