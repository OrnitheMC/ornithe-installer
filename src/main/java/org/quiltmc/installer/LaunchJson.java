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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class LaunchJson {
	/**
	 * @return the launch json for a vanilla mc instance
	 */
	public static CompletableFuture<String> get(VersionManifest.Version gameVersion) {
		String rawUrl = String.format(VersionManifest.VERSION_META_URL, gameVersion.id());

		return CompletableFuture.supplyAsync(() -> {
			try {
				URL url = new URL(rawUrl);
				URLConnection connection = Connections.openConnection(url);
				Map<String, Object> map;

				try (InputStreamReader input = new InputStreamReader(connection.getInputStream())) {
					map = (Map<String, Object>) Gsons.read(JsonReader.json(input));
				}

				for (String rawManifestUrl : gameVersion.details().manifests()) {
					URL manifestUrl = new URL(rawManifestUrl);
					URLConnection manifestConnection = Connections.openConnection(manifestUrl);

					try (InputStreamReader input = new InputStreamReader(manifestConnection.getInputStream())) {
						buildVersionJsonFromManifest(map, (Map<String, Object>) Gsons.read(JsonReader.json(input)));
					}
				}

				map.put("id", String.format("%s-vanilla", gameVersion.id()));

				StringWriter writer = new StringWriter();
				Gsons.write(JsonWriter.json(writer), map);

				return writer.toString();
			} catch (IOException e) {
				throw new UncheckedIOException(e); // Handled via .exceptionally(...)
			}
		});
	}

	private static void buildVersionJsonFromManifest(Map<String, Object> versionJson, Map<String, Object> manifest) {
		for (String key : manifest.keySet()) {
			if (versionJson.containsKey(key)) {
				Object versionJsonElement = versionJson.get(key);
				Object manifestElement = manifest.get(key);

				if (versionJsonElement.equals(manifestElement)) {
					// version json already contains this element, continue
				} else {
					// check if elements are objects and combine them
					if (versionJsonElement instanceof Map && manifestElement instanceof Map) {
						buildVersionJsonFromManifest((Map<String, Object>) versionJsonElement, (Map<String, Object>) manifestElement);
					}
				}
			} else {
				// version json does not have this element yet; add it
				versionJson.put(key, manifest.get(key));
			}
		}
	}

	/**
	 * @return the launch json for a modded mc instance
	 */
	public static CompletableFuture<String> get(GameSide side, VersionManifest.Version gameVersion, LoaderType type, String loaderVersion, boolean beaconOptOut) {
		String rawUrl = OrnitheMeta.ORNITHE_META_URL + String.format(side.launchJsonEndpoint(), type.getName(), gameVersion.id(side), loaderVersion);

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
		}).thenApplyAsync(raw -> {
			Map<String, Object> map;
			try {
				//noinspection unchecked
				map = (Map<String, Object>) Gsons.read(JsonReader.json(raw));
			} catch (IOException e) {
				throw new UncheckedIOException(e); // Handled via .exceptionally(...)
			}

			if (beaconOptOut) {
				@SuppressWarnings("unchecked")
				Map<String, List<Object>> arguments = (Map<String,List<Object>>)map.get("arguments");
				arguments
						.computeIfAbsent("jvm", (key) -> new ArrayList<>())
						.add("-Dloader.disable_beacon=true");
			}

			// TODO: HACK HACK HACK: inject intermediary instead of hashed
			@SuppressWarnings("unchecked") List<Map<String, String>> libraries = (List<Map<String, String>>) map.get("libraries");
			for (Map<String, String> library : libraries) {
				if (library.get("name").startsWith("net.fabricmc:intermediary")) {
					library.replace("name", library.get("name").replace("net.fabricmc:intermediary", "net.ornithemc:calamus-intermediary"));
					library.replace("url", "https://maven.ornithemc.net/releases/");
				}
				if (library.get("name").startsWith("org.quiltmc:hashed")) {
					library.replace("name", library.get("name").replace("org.quiltmc:hashed", "net.ornithemc:calamus-intermediary"));
					library.replace("url", "https://maven.ornithemc.net/releases/");
				}
			}
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
