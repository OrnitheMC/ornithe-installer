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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MmcPackCreator {
	private static final String ENV_WRAPPER_COMMAND = "WrapperCommand=env __GL_THREADED_OPTIMIZATIONS=0";
	private static final boolean IS_LINUX_LIKE_OS;

	private static LWJGL findLwjgl(VersionManifest manifest, String gameVersion) {
		VersionManifest.Version version = manifest.getVersion(gameVersion);

		try {
			URL url = new URL(version.url());
			URLConnection connection = Connections.openConnection(url);

			try (JsonReader reader = JsonReader.json(new BufferedReader(new InputStreamReader(connection.getInputStream())))) {
				LWJGL lwjgl = findLwjgl(reader);

				if (lwjgl != null) {
					return lwjgl;
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("issue while finding lwjgl version for Minecraft " + gameVersion, e);
		}

		throw new RuntimeException("unable to find lwjgl version for Minecraft " + gameVersion);
	}

	private static LWJGL findLwjgl(JsonReader reader) throws IOException, ParseException {
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

						String lwjglVersion = null;
						String lwjglUrl = null;

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
										lwjglVersion = version;
									}

									break;
								case "downloads":
									if (reader.peek() != JsonToken.BEGIN_OBJECT) {
										reader.skipValue();
									} else {
										reader.beginObject();

										while (reader.hasNext()) {
											switch (reader.nextName()) {
											case "artifact":
												if (reader.peek() != JsonToken.BEGIN_OBJECT) {
													reader.skipValue();
												} else {
													reader.beginObject();

													while (reader.hasNext()) {
														switch (reader.nextName()) {
														case "url":
															if (reader.peek() != JsonToken.STRING) {
																throw new ParseException("download artifact url must be a string", reader);
															}

															String url = reader.nextString();

															if (url.contains("lwjgl")) {
																lwjglUrl = url;
															}

															break;
														default:
															reader.skipValue();
														}
													}

													reader.endObject();
												}

												break;
											default:
												reader.skipValue();
											}
										}

										reader.endObject();
									}

									break;
								default:
									reader.skipValue();
							}
						}

						reader.endObject();

						if (lwjglVersion != null && lwjglUrl != null) {
							return new LWJGL(lwjglVersion, lwjglUrl);
						}
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

	private static String transformPackJson(String examplePackJson, String gameVersion, LoaderType type, String loaderVersion, LWJGL lwjgl, String intermediaryVersion) {
		return examplePackJson
				.replaceAll("\\$\\{mc_version}", gameVersion)
				.replaceAll("\\$\\{intermediary_ver}", intermediaryVersion)
				.replaceAll("\\$\\{loader_version}", loaderVersion)
				.replaceAll("\\$\\{loader_name}", type.getLocalizedName() + " Loader")
				.replaceAll("\\$\\{loader_uid}", type.getMavenUid())
				.replaceAll("\\$\\{lwjgl_version}", lwjgl.getVersion())
				.replaceAll("\\$\\{lwjgl_major_ver}", lwjgl.getMajorVersion())
				.replaceAll("\\$\\{lwjgl_uid}", lwjgl.getUid());
	}

	private static String transformMinecraftJson(String minecraftPatchString, LWJGL lwjgl) {
		return minecraftPatchString
				.replaceAll("\\$\\{lwjgl_version}", lwjgl.getVersion())
				.replaceAll("\\$\\{lwjgl_uid}", lwjgl.getUid());
	}

	private static String addLibraryUpgrades(Path instanceZipRoot, String gameVersion, LoaderType loaderType, String loaderVersion, OptionalInt intermediaryGen, Intermediary intermediary, String packJson) throws IOException {
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
				OrnitheMeta.libraryUpgradesEndpoint(intermediaryGen, gameVersion);
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

			Files.write(instanceZipRoot.resolve("patches").resolve(uid + ".json"),
					String.format(patch, name, url, libName, uid, version).getBytes(StandardCharsets.UTF_8));
			components.add(new HashMap<String, Object>() {{
				put("cachedName", libName);
				put("cachedVersion", version);
				put("uid", uid);
			}});
		}



		StringWriter writer = new StringWriter();
		Gsons.write(JsonWriter.json(writer), pack);
		return writer.toString();


	}

	public static void compileMmcZip(Path outPutDir, String gameVersion, LoaderType loaderType, String loaderVersion, OptionalInt intermediaryGen, Intermediary intermediary, VersionManifest manifest, boolean copyProfilePath) {

		String examplePackDir = "/packformat";
		String packJsonPath = "mmc-pack.json";
		String intermediaryJsonPath = "patches/net.fabricmc.intermediary.json";
		String lwjglJsonPath = "patches/org.lwjgl.json";
		String instanceCfgPath = "instance.cfg";
		String iconPath = "ornithe.png";
		String minecraftPatchPath = "patches/net.minecraft.json";

		VersionManifest.Version version = manifest.getVersion(gameVersion);
		String intermediaryMavenNotation = intermediary.getMavenNotation();
		String intermediaryArtifact = intermediaryMavenNotation.substring(0, intermediaryMavenNotation.lastIndexOf(':'));
		String intermediaryVersion = intermediary.getVersion();

		try {
			LWJGL lwjgl = findLwjgl(manifest, gameVersion);

			String transformedPackJson = transformPackJson(
					readResource(examplePackDir, packJsonPath), gameVersion, loaderType, loaderVersion, lwjgl, intermediaryVersion
			);
			String transformedIntermediaryJson = readResource(examplePackDir, intermediaryJsonPath)
					.replaceAll("\\$\\{mc_version}", gameVersion)
					.replaceAll("\\$\\{intermediary_ver}", intermediaryVersion)
					.replaceAll("\\$\\{intermediary_maven}", intermediaryArtifact);

			String transformedLwjglJson = readResource(examplePackDir, lwjglJsonPath)
					.replaceAll("\\$\\{lwjgl_version}", lwjgl.getVersion())
					.replaceAll("\\$\\{lwjgl_major_ver}", lwjgl.getMajorVersion())
					.replaceAll("\\$\\{lwjgl_uid}", lwjgl.getUid());;

			String transformedInstanceCfg = readResource(examplePackDir, instanceCfgPath)
					.replaceAll("\\$\\{intermediary_generation}", String.valueOf(intermediaryGen.orElseGet(IntermediaryGenerations::stable)))
					.replaceAll("\\$\\{loader_type}", loaderType.getLocalizedName())
					.replaceAll("\\$\\{mc_version}", gameVersion);

			String transformedMinecraftJson = transformMinecraftJson(
					LaunchJson.getMmcJson(version, intermediaryGen, intermediary, loaderType, loaderVersion).join(), lwjgl
			);

			if (IS_LINUX_LIKE_OS) {
				transformedInstanceCfg += "\n" + "OverrideCommands=true" + "\n" + ENV_WRAPPER_COMMAND;
			}

			Path zipFile = outPutDir.resolve("Ornithe Gen" + intermediaryGen.orElseGet(IntermediaryGenerations::stable) + " " + loaderType.getLocalizedName() + " " + gameVersion + ".zip");
			Files.deleteIfExists(zipFile);

			// This is a god awful workaround, because paths can't be cleanly converted to URIs in j8, and for some reason, you can't pass parameters into newFileSystem with a path argument.
			// Thanks Java :)
			ZipOutputStream dummyZipOutputStream = new ZipOutputStream(Files.newOutputStream(zipFile.toFile().toPath()));
			// I need to put an entry inside, or it creates a 0-byte file, which filesystem doesn't like
			dummyZipOutputStream.putNextEntry(new ZipEntry("mmc-pack.json"));
			dummyZipOutputStream.write("if you see this, this didn't work".getBytes(StandardCharsets.UTF_8));
			dummyZipOutputStream.closeEntry();
			dummyZipOutputStream.close();
			// End god awful workaround

			// And now we load that dummy zip as a filesystem and actually make it real.
			try (FileSystem fs = FileSystems.newFileSystem(zipFile, (ClassLoader) null)) {
				Files.copy(MmcPackCreator.class.getResourceAsStream(examplePackDir + "/" + iconPath), fs.getPath(iconPath));
				Files.write(fs.getPath(instanceCfgPath), transformedInstanceCfg.getBytes(StandardCharsets.UTF_8));
				Files.createDirectory(fs.getPath("patches"));
				Files.write(fs.getPath(intermediaryJsonPath), transformedIntermediaryJson.getBytes(StandardCharsets.UTF_8));
				if (lwjgl.isCustom()) {
					Files.write(fs.getPath(lwjglJsonPath), transformedLwjglJson.getBytes(StandardCharsets.UTF_8));
				}
				Files.write(fs.getPath(minecraftPatchPath), transformedMinecraftJson.getBytes(StandardCharsets.UTF_8));
				String packJsonWithLibraries = addLibraryUpgrades(fs.getPath("/"), gameVersion,
						loaderType, loaderVersion, intermediaryGen, intermediary, transformedPackJson);

				Files.write(fs.getPath(packJsonPath), packJsonWithLibraries.getBytes(StandardCharsets.UTF_8));
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
		return os.toString(StandardCharsets.UTF_8.name());
	}

	static {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		IS_LINUX_LIKE_OS = os.contains("linux") || !(os.contains("win") || os.contains("mac"));
	}
}
