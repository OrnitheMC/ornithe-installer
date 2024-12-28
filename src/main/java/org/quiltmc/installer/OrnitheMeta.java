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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.quiltmc.parsers.json.JsonReader;
import org.quiltmc.parsers.json.JsonToken;

public final class OrnitheMeta {
	public static final Endpoint<List<String>> FABRIC_LOADER_VERSIONS_ENDPOINT = createVersion("/v3/versions/fabric-loader");
	public static final Endpoint<List<String>> QUILT_LOADER_VERSIONS_ENDPOINT = createVersion("/v3/versions/quilt-loader");

	@SuppressWarnings("unchecked")
	public static Endpoint<List<Map<String, String>>> profileLibrariesEndpoint(String version, LoaderType type, String loaderVersion){
		String loader = switch (type){
			case FABRIC -> "fabric-loader";
			case QUILT -> "quilt-loader";
		};
		return new Endpoint<>(String.format("/v3/versions/%s/%s/%s/profile/json", loader, version, loaderVersion), reader -> {

			List<Map<String, String>> libraries = new ArrayList<>();
			Map<String, Object> map = (Map<String, Object>) Gsons.read(reader);
			List<Map<String, String>> json = (List<Map<String, String>>) map.get("libraries");
			json.forEach(s -> {
				if (s.get("url").equals("https://libraries.minecraft.net/")){
					libraries.add(s);
				}
			});
			return libraries;
		});
	}

	public static Endpoint<List<String>> loaderVersionsEndpoint(LoaderType type) {
		switch (type) {
		case FABRIC:
			return FABRIC_LOADER_VERSIONS_ENDPOINT;
		case QUILT:
			return QUILT_LOADER_VERSIONS_ENDPOINT;
		}

		throw new IllegalStateException("no endpoints for loader type " + type.getName());
	}

	/**
	 * An endpoint for intermediary versions.
	 *
	 * <p>The returned map has the version as the key and the maven artifact as the value
	 */
	public static final Endpoint<Map<String, String>> INTERMEDIARY_VERSIONS_ENDPOINT = new Endpoint<>("/v3/versions/intermediary", reader -> {
		Map<String, String> ret = new LinkedHashMap<>();

		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseException("Intermediary versions must be in an array", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			if (reader.peek() != JsonToken.BEGIN_OBJECT) {
				throw new ParseException("Intermediary version entry must be an object", reader);
			}

			reader.beginObject();

			String version = null;
			String maven = null;

			while (reader.hasNext()) {
				if (reader.peek() != JsonToken.NAME) {
					reader.skipValue();
					continue;
				}
                switch (reader.nextName()) {
                    case "version" -> {
                        if (reader.peek() != JsonToken.STRING) {
                            throw new ParseException("Version must be a string", reader);
                        }
                        version = reader.nextString();
                    }
                    case "maven" -> {
                        if (reader.peek() != JsonToken.STRING) {
                            throw new ParseException("maven must be a string", reader);
                        }
                        maven = reader.nextString();
                    }
                    case "stable" -> reader.nextBoolean(); // TODO
                }
			}

			if (version == null) {
				throw new ParseException("Intermediary version entry does not have a version field", reader);
			}

			if (maven == null) {
				throw new ParseException("Intermediary version entry does not have a maven field", reader);
			}

			ret.put(version, maven);

			reader.endObject();
		}

		reader.endArray();

		return ret;
	});

	public static final String ORNITHE_META_URL = "https://meta.ornithemc.net";
	private final Map<Endpoint<?>, Object> endpoints;

	public static CompletableFuture<OrnitheMeta> create(String baseMetaUrl, Set<Endpoint<?>> endpoints) {
		Map<Endpoint<?>, CompletableFuture<?>> futures = new HashMap<>();
		for (Endpoint<?> endpoint : endpoints) {
			futures.put(endpoint, CompletableFuture.supplyAsync(() -> {
				try {
					URL url = new URL(baseMetaUrl + endpoint.endpointPath);

					URLConnection connection = Connections.openConnection(url);

					InputStreamReader stream = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8);

					try (JsonReader reader = JsonReader.json(new BufferedReader(stream))) {
						return endpoint.deserializer.apply(reader);
					}
				} catch (IOException e) {
					throw new UncheckedIOException(e); // Handled via .exceptionally(...)
				}
			}));
		}

		CompletableFuture<Void> future = CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]));

		return future.thenApply(_v -> {
			Map<Endpoint<?>, Object> resolvedEndpoints = new HashMap<>();

			for (Map.Entry<Endpoint<?>, CompletableFuture<?>> entry : futures.entrySet()) {
				resolvedEndpoints.put(entry.getKey(), entry.getValue().join());
			}

			return new OrnitheMeta(baseMetaUrl, resolvedEndpoints);
		});
	}

	private static Endpoint<List<String>> createVersion(String endpointPath) {
		return new Endpoint<>(endpointPath, reader -> {
			if (reader.peek() != JsonToken.BEGIN_ARRAY) {
				throw new ParseException("Result of endpoint must be an object", reader);
			}

			List<String> versions = new ArrayList<>();
			reader.beginArray();

			while (reader.hasNext()) {
				if (reader.peek() != JsonToken.BEGIN_OBJECT) {
					throw new ParseException("Version entry must be an object", reader);
				}

				String version = null;
				reader.beginObject();

				while (reader.hasNext()) {
					String key = reader.nextName();

					if ("version".equals(key)) {
						if (reader.peek() != JsonToken.STRING) {
							throw new ParseException("\"version\" in entry must be a string", reader);
						}

						version = reader.nextString();
					} else {
						reader.skipValue();
					}
				}

				if (version == null) {
					throw new ParseException("\"version\" field is required in a version entry", reader);
				}

				versions.add(version);

				reader.endObject();
			}

			reader.endArray();

			return versions;
		});
	}

	private OrnitheMeta(String baseMetaUrl, Map<Endpoint<?>, Object> endpoints) {
		this.endpoints = endpoints;
	}

	public <T> T getEndpoint(Endpoint<T> endpoint) {
		Objects.requireNonNull(endpoint, "Endpoint cannot be null");

		@SuppressWarnings("unchecked")
		T result = (T) this.endpoints.get(endpoint);

		if (result == null) {
			throw new IllegalArgumentException("Endpoint had no value!");
		}

		return result;
	}

	public static final class Endpoint<T> {
		private final String endpointPath;
		private final ThrowingFunction<JsonReader, T, ParseException> deserializer;

		Endpoint(String endpointPath, ThrowingFunction<JsonReader, T, ParseException> deserializer) {
			this.endpointPath = endpointPath;
			this.deserializer = deserializer;
		}

		@Override
		public String toString() {
			return "Endpoint{endpointPath=\"" + this.endpointPath + "\"";
		}
	}
}
