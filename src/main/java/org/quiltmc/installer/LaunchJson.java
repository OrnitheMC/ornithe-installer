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
import org.quiltmc.parsers.json.JsonWriter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class LaunchJson {

	@SuppressWarnings("unchecked")
	public static CompletableFuture<String> getMmcJson(VersionManifest.Version gameVersion){
		return LaunchJson.get(gameVersion).thenApply(
				vanillaJson ->  {
					try {
						Map<String, Object> vanillaMap = (Map<String, Object>) Gsons.read(JsonReader.json(vanillaJson));

						String clientName = "com.mojang:minecraft:" + gameVersion.id() + ":client";
						Map<String,Map<String, String>> downloads = (Map<String, Map<String, String>>) vanillaMap.get("downloads");
						Map<String, String> client = downloads.get("client");

						Map<String, Object> mainJar = Map.of(
								"downloads", Map.of("artifact",client),
								"name",clientName
						);

						// remove lwjgl as it is handled separately by the pack generator
						List<Map<String, String>> vanillaLibraries = (List<Map<String, String>>) vanillaMap.get("libraries");
						vanillaLibraries.removeIf(lib -> lib.get("name").contains("org.lwjgl"));

						List<String> traits = new ArrayList<>();
						if (((String) vanillaMap.get("mainClass")).contains("launchwrapper")) {
							traits.add("texturepacks");
						}

						String minecraftArguments = (String) vanillaMap.getOrDefault("minecraftArguments", "");
						if (vanillaMap.containsKey("arguments")) {
							Map<String, Object> arguments =  ((Map<String, Object>) vanillaMap.get("arguments"));//.get("game");

							if(!arguments.isEmpty()){
								List<Object> gameArguments = (List<Object>) arguments.get("game");

								if (!arguments.isEmpty()) {
									String combinedCombination = "";
									for (Object gameArgument : gameArguments) { // custom res and demo args are not needed with mmc
										if (gameArgument instanceof String) {
											combinedCombination += gameArgument + " ";
										}
									}
									minecraftArguments = combinedCombination.trim();
									// TODO this is bit of a hack? ideally should derive this from the jvm args list of the arguments object,
									//  but every version that has a game arguments list has this trait so unless manifests change this works
									traits.add("FirstThreadOnMacOS");
								}
							}
						}

						StringWriter writer = new StringWriter();
						Gsons.write(
								JsonWriter.json(writer),
								buildPackJsonMap(
										vanillaMap, vanillaLibraries, minecraftArguments, traits, mainJar, gameVersion.id()
								)
						);

						return writer.toString();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
		);
	}


	private static Map<String, Object> buildPackJsonMap(
			Map<String, Object> vanilaMap,
			List<Map<String, String>> modifiedLibraries,
			String minecraftArguments,
			List<String> traits,
			Map<String, Object> mainJar,
			String gameVersion
	){
		Map<String, Object> moddedJsonMap = new LinkedHashMap<>();

		if(!traits.isEmpty()){
			moddedJsonMap.put("+traits",traits);
		}

		moddedJsonMap.put("assetIndex",vanilaMap.get("assetIndex"));
		moddedJsonMap.put("compatibleJavaMajors", List.of(8, 17, 21, 25));
		moddedJsonMap.put("compatibleJavaName", "java-runtime-epsilon");
		moddedJsonMap.put("formatVersion", 1);
		moddedJsonMap.put("libraries", modifiedLibraries);
		moddedJsonMap.put("mainClass", vanilaMap.get("mainClass"));
		moddedJsonMap.put("mainJar", mainJar);
		moddedJsonMap.put("minecraftArguments", minecraftArguments);
		moddedJsonMap.put("name", "Minecraft");
		moddedJsonMap.put("releaseTime", vanilaMap.get("releaseTime"));
		moddedJsonMap.put("requires",List.of(
				Map.of(
						"suggests", "${lwjgl_version}",
						"uid", "${lwjgl_uid}"
				)
		));
		moddedJsonMap.put("type", vanilaMap.get("type"));
		moddedJsonMap.put("uid", "net.minecraft");
		moddedJsonMap.put("version", gameVersion);

		return moddedJsonMap;
	}

	/**
	 * @return the launch json for a vanilla mc instance
	 */
	public static CompletableFuture<String> get(VersionManifest.Version gameVersion) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				URL url = new URL(gameVersion.url());
				URLConnection connection = Connections.openConnection(url);
				Map<String, Object> map;

				try (InputStreamReader input = new InputStreamReader(connection.getInputStream())) {
					map = (Map<String, Object>) Gsons.read(JsonReader.json(input));
				}

				// add the -vanilla suffix to the vanilla json 'cause
				// we use a different version manifest than mojang and
				// some version ids can differ from the official ones
				map.put("id", String.format("%s-vanilla", gameVersion.id()));

				StringWriter writer = new StringWriter();
				Gsons.write(JsonWriter.json(writer), map);

				return writer.toString();
			} catch (IOException e) {
				throw new UncheckedIOException(e); // Handled via .exceptionally(...)
			}
		});
	}

	/**
	 * @return the launch json for a modded mc instance
	 */
	public static CompletableFuture<String> get(GameSide side, VersionManifest.Version gameVersion, OptionalInt intermediaryGen, Intermediary intermediary, LoaderType loaderType, String loaderVersion) {
		String rawUrl = OrnitheMeta.ORNITHE_META_URL + OrnitheMeta.launchJsonEndpointPath(side, loaderType, loaderVersion, intermediaryGen, intermediary);

		return CompletableFuture.supplyAsync(() -> {
			try {
				URL url = new URL(rawUrl);
				URLConnection connection = Connections.openConnection(url);

				InputStreamReader stream = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8);

				try (BufferedReader reader = new BufferedReader(stream)) {
					StringBuilder builder = new StringBuilder();
					String line;

					while ((line = reader.readLine()) != null) {
						builder.append(line);
						builder.append('\n');
					}

					return builder.toString();
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e); // Handled via .exceptionally(...)
			}
			// TODO: HACK HACK HACK: inject intermediary instead of hashed
		}).thenApplyAsync(raw -> {
			Map<String, Object> map;
			try {
				//noinspection unchecked
				map = (Map<String, Object>) Gsons.read(JsonReader.json(raw));
			} catch (IOException e) {
				throw new UncheckedIOException(e); // Handled via .exceptionally(...)
			}

			// we apply the library upgrades only to the Ornithe instance, not the Vanilla instance
			OrnitheMeta.Endpoint<List<Map<String, String>>> libraryUpgradesEndpoint = OrnitheMeta.libraryUpgradesEndpoint(intermediaryGen, gameVersion.id());
			OrnitheMeta meta = OrnitheMeta.create(OrnitheMeta.ORNITHE_META_URL, Collections.singleton(libraryUpgradesEndpoint)).join();
			List<Map<String, String>> libraryUpgrades = meta.getEndpoint(libraryUpgradesEndpoint);

			if (loaderType == LoaderType.QUILT) {
				// Prevents a log warning about being unable to reach the active user beacon on stable versions.
				switch (loaderVersion) {
					case "0.19.2", "0.19.3", "0.19.4" -> {
						@SuppressWarnings("unchecked")
						Map<String, List<Object>> arguments = (Map<String,List<Object>>)map.get("arguments");
						arguments
								.computeIfAbsent("jvm", (key) -> new ArrayList<>())
								.add("-Dloader.disable_beacon=true");
					}
					default -> {
						// do nothing
					}
				}
			}

			@SuppressWarnings("unchecked")
			List<Map<String, String>> libraries = (List<Map<String, String>>) map.get("libraries");

			libraries.addAll(libraryUpgrades);

			StringWriter writer = new StringWriter();
			try {
				Gsons.write(JsonWriter.json(writer), map);
			} catch (IOException e) {
				throw new UncheckedIOException(e); // Handled via .exceptionally(...)
			}
			return writer.toString();
		});
	}

	private LaunchJson() {
	}
}
