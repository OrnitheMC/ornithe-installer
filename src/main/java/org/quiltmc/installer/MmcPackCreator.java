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

import org.quiltmc.parsers.json.JsonReader;
import org.quiltmc.parsers.json.JsonToken;
import org.quiltmc.parsers.json.JsonWriter;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class MmcPackCreator {
	private static final String ENV_WRAPPER_COMMAND = "WrapperCommand=env __GL_THREADED_OPTIMIZATIONS=0";
	private static final boolean IS_LINUX_LIKE_OS;
	//private static final Semver VERSION_1_6 = new Semver("1.6.0-pre+06251516");
	//private static final Semver VERSION_1_3 = new Semver("1.3.0-pre+07261249");

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

	private static String transformPackJson(String examplePackJson, String gameVersion, LoaderType type, String loaderVersion, String lwjglVersion, String intermediaryVersion) {
		String lwjglMajorVer = lwjglVersion.substring(0, 1);
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

	private static String transformMinecraftJson(String minecraftPatchString, String lwjglVersion) {
		String lwjglMajorVer = lwjglVersion.substring(0, 1);
		return minecraftPatchString
				.replaceAll("\\$\\{lwjgl_version}", lwjglVersion)
				.replaceAll("\\$\\{lwjgl_uid}", lwjglMajorVer.equals("3") ? "org.lwjgl3" : "org.lwjgl");
	}

	private static String addLibraryUpgrades(Path instanceZipRoot, String gameVersion, LoaderType loaderType, String loaderVersion, String packJson) throws IOException {
		String patch = "{\"formatVersion\": 1, " +
				"\"libraries\": " +
				"[{\"name\": \"%s\"," +
				"\"url\": \"%s\"}]," +
				"\"name\": \"%s\"," +
				"\"type\": \"release\"," +
				"\"uid\": \"%s\"," +
				"\"version\": \"%s\"" +
				"}";
		OrnitheMeta.Endpoint<List<Map<String, String>>> librariesEndpoint =
				OrnitheMeta.libraryUpgradesEndpoint(gameVersion);
		OrnitheMeta meta = OrnitheMeta.create(OrnitheMeta.ORNITHE_META_URL, Collections.singleton(librariesEndpoint))
				.join();

		List<Map<String, String>> libraries = meta.getEndpoint(librariesEndpoint);
		@SuppressWarnings("unchecked")
		Map<String, Object> pack = (Map<String, Object>) Gsons.read(JsonReader.json(packJson));
		@SuppressWarnings("unchecked")
		List<Map<String, ?>> components = (List<Map<String, ?>>) pack.get("components");
		for (Map<String, String> map : libraries) {
			String name = map.get("name");
			String url = map.get("url");
			String uid = name.substring(0, name.lastIndexOf(':')).replace(":", ".");
			String libName = name.substring(name.indexOf(':')+1, name.lastIndexOf(':'));
			String version = name.substring(name.lastIndexOf(':')+1);

			Files.writeString(instanceZipRoot.resolve("patches").resolve(uid + ".json"),
					String.format(patch, name, url, libName, uid, version));
			components.add(Map.of("cachedName", libName,"cachedVersion", version,"uid", uid));
		}



		StringWriter writer = new StringWriter();
		Gsons.write(JsonWriter.json(writer), pack);
		return writer.toString();


	}

	public static void compileMmcZip(Path outPutDir, String gameVersion, LoaderType loaderType, String loaderVersion, String intermediaryInfo, VersionManifest manifest, boolean copyProfilePath) {

		String examplePackDir = "/packformat";
		String packJsonPath = "mmc-pack.json";
		String intermediaryJsonPath = "patches/net.fabricmc.intermediary.json";
		String instanceCfgPath = "instance.cfg";
		String iconPath = "ornithe.png";
		String minecraftPatchPath = "patches/net.minecraft.json";

		VersionManifest.Version version = manifest.getVersion(gameVersion);
		String[] intermediaryParts = intermediaryInfo.split("[:]");
		String intermediaryMaven = intermediaryParts[0] + ":" + intermediaryParts[1];
		String intermediaryVersion = intermediaryParts[2];

		try {
			String lwjglVersion = findLwjglVersion(manifest, gameVersion);

			String transformedPackJson = transformPackJson(
					readResource(examplePackDir, packJsonPath), gameVersion, loaderType, loaderVersion, lwjglVersion, intermediaryVersion
			);
			String transformedIntermediaryJson = readResource(examplePackDir, intermediaryJsonPath)
					.replaceAll("\\$\\{mc_version}", gameVersion)
					.replaceAll("\\$\\{intermediary_ver}", intermediaryVersion)
					.replaceAll("\\$\\{intermediary_maven}", intermediaryMaven);

			String transformedInstanceCfg = readResource(examplePackDir, instanceCfgPath)
					.replaceAll("\\$\\{mc_version}", gameVersion);

			String transformedMinecraftJson = transformMinecraftJson(
					LaunchJson.getMmcJson(version).join(), lwjglVersion
			);

			if (IS_LINUX_LIKE_OS) {
				transformedInstanceCfg += "\n" + "OverrideCommands=true" + "\n" + ENV_WRAPPER_COMMAND;
			}

			Path zipFile = outPutDir.resolve("Ornithe-" + gameVersion + ".zip");
			Files.deleteIfExists(zipFile);

			try (FileSystem fs = FileSystems.newFileSystem(zipFile, Map.of("create", "true"))) {
				Files.copy(MmcPackCreator.class.getResourceAsStream(examplePackDir + "/" + iconPath), fs.getPath(iconPath));
				Files.writeString(fs.getPath(instanceCfgPath), transformedInstanceCfg);
				Files.createDirectory(fs.getPath("patches"));
				Files.writeString(fs.getPath(intermediaryJsonPath), transformedIntermediaryJson);
				Files.writeString(fs.getPath(minecraftPatchPath), transformedMinecraftJson);
				String packJsonWithLibraries = addLibraryUpgrades(fs.getPath("/"), gameVersion,
						loaderType, loaderVersion, transformedPackJson);

				Files.writeString(fs.getPath(packJsonPath), packJsonWithLibraries);
			}

			if (copyProfilePath) {
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(zipFile.toString()), null);
			}

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

	static {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		IS_LINUX_LIKE_OS = os.contains("linux") || !(os.contains("win") || os.contains("mac"));
	}
}
