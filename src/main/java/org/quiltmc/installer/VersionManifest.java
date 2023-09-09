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
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.Nullable;
import org.quiltmc.parsers.json.JsonReader;
import org.quiltmc.parsers.json.JsonToken;

/**
 * An object representation of the version manifest used by the launcher.
 */
// TODO: Abstract to another library for sharing logic with meta?
public final class VersionManifest implements Iterable<VersionManifest.Version> {
	public static final String LAUNCHER_META_URL = "https://skyrising.github.io/mc-versions/version_manifest.json";
	public static final String VERSION_META_URL = "https://skyrising.github.io/mc-versions/version/manifest/%s.json";
	private final Version latestRelease;
	private final Version latestSnapshot;
	private final Map<String, Version> versions;

	public static CompletableFuture<VersionManifest> create() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				URL url = new URL(LAUNCHER_META_URL);
				URLConnection connection = Connections.openConnection(url);

				InputStreamReader stream = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8);

				try (JsonReader reader = JsonReader.json(new BufferedReader(stream))) {
					return read(reader);
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e); // Handled via .exceptionally(...)
			}
		});
	}

	private static VersionManifest read(JsonReader reader) throws IOException, ParseException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseException("Launcher Meta was invalid type", reader);
		}

		// Read state
		// Must be linked hashmap to preserve ordering
		Map<String, Version> versions = new LinkedHashMap<>();
		@Nullable
		String latestRelease = null;
		@Nullable
		String latestSnapshot = null;

		reader.beginObject();

		while (reader.hasNext()) {
			String key = reader.nextName();

			switch (key) {
			case "latest":
				if (reader.peek() != JsonToken.BEGIN_OBJECT) {
					throw new ParseException("Latest versions must be an object", reader);
				}

				reader.beginObject();

				while (reader.hasNext()) {
					String latestType = reader.nextName();

					if (reader.peek() != JsonToken.STRING) {
						throw new ParseException(String.format("Latest \"%s\" must have a string value", latestType), reader);
					}

					switch (latestType) {
					case "release":
						latestRelease = reader.nextString();
						break;
					case "snapshot":
						latestSnapshot = reader.nextString();
						break;
					default:
						reader.skipValue();
					}
				}

				reader.endObject();

				break;
			case "versions":
				readVersions(reader, versions);
				break;
			default:
				reader.skipValue();
			}
		}

		reader.endObject();

		// Resolve latest release and snapshot instance
		Version latestReleaseVersion = versions.get(latestRelease);
		Version latestSnapshotVersion = versions.get(latestSnapshot);

		return new VersionManifest(latestReleaseVersion, latestSnapshotVersion, versions);
	}

	private static void readVersions(JsonReader reader, Map<String, Version> versions) throws IOException, ParseException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseException("Versions must be in an array", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			if (reader.peek() != JsonToken.BEGIN_OBJECT) {
				throw new ParseException("Version entries must all be objects", reader);
			}

			reader.beginObject();

			// Read values
			String id = null;
			String type = null;
			String url = null;
			String time = null;
			String releaseTime = null;
			String details = null;

			while (reader.hasNext()) {
				String key = reader.nextName();

				switch (key) {
				case "id":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseException("Version id must be a string", reader);
					}

					id = reader.nextString();
					break;
				case "type":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseException("Version type must be a string", reader);
					}

					type = reader.nextString();
					break;
				case "url":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseException("Version url must be a string", reader);
					}

					url = reader.nextString();
					break;
				case "time":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseException("Version time must be a string", reader);
					}

					time = reader.nextString();
					break;
				case "releaseTime":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseException("Version release time must be a string", reader);
					}

					releaseTime = reader.nextString();
					break;
				case "details":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseException("Details url must be a string", reader);
					}

					details = reader.nextString();
					break;
				// v2 adds sha1 and complianceLevel, we do not need those
				default:
					reader.skipValue();
				}
			}

			reader.endObject();

			if (id == null) throw new ParseException("Version id is required", reader);
			if (type == null) throw new ParseException("Version type is required", reader);
			if (url == null) throw new ParseException("Version url is required", reader);
			// neither of these are actually used and versions prior to 1.2.5 do not
			// always have this information in the manifest so we just ignore it
//			if (time == null) throw new ParseException("Version time is required", reader);
//			if (releaseTime == null) throw new ParseException("Version release time is required", reader);
			if (details == null) throw new ParseException("Details url is required", reader);

			versions.put(id, new Version(id, type, url, time, releaseTime, details));
		}

		reader.endArray();
	}

	private static VersionDetails readDetails(Version version) {
		try {
			URL url = new URL(version.detailsUrl);
			URLConnection connection = url.openConnection();

			InputStreamReader stream = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8);

			try (JsonReader reader = JsonReader.json(new BufferedReader(stream))) {
				return readDetails(version, reader);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e); // Handled via .exceptionally(...)
		}
	}

	private static VersionDetails readDetails(Version version, JsonReader reader) throws IOException, ParseException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseException("Version Details was invalid type", reader);
		}

		List<String> manifests = null;
		Boolean sharedMappings = null;
		String lwjglVersion = null;

		reader.beginObject();

		while (reader.hasNext()) {
			String key = reader.nextName();

			switch (key) {
			case "manifests":
				if (reader.peek() != JsonToken.BEGIN_ARRAY) {
					throw new ParseException("manifests must be an array", reader);
				}

				manifests = readManifests(version, reader);
				break;
			case "sharedMappings":
				if (reader.peek() != JsonToken.BOOLEAN) {
					throw new ParseException("sharedMappings must be a boolean", reader);
				}

				sharedMappings = reader.nextBoolean();
				break;
			case "libraries" :
				if(reader.peek() != JsonToken.BEGIN_ARRAY){
					throw new ParseException("libraries must be an array", reader);
				}
				lwjglVersion = getLwjglVersion(reader);
				break;
			default:
				reader.skipValue();
			}
		}

		reader.endObject();

		if (manifests == null) throw new ParseException("manifests is required", reader);
		if (sharedMappings == null) throw new ParseException("sharedMappings is required", reader);
		if (version == null) throw new ParseException("LWJGL version is required", reader);

		return new VersionDetails(version, manifests, lwjglVersion, sharedMappings);
	}

	private static String getLwjglVersion(JsonReader reader) throws IOException {
		String version = null;

		reader.beginArray();

		while (reader.hasNext()) {
			if (reader.peek() != JsonToken.STRING) {
				throw new ParseException("library array entries must be strings", reader);
			}

			String name = reader.nextString();
			if (name.startsWith("org.lwjgl:lwjgl:")) {
				version = name.substring(16);
				break;
			} else if (name.startsWith("org.lwjgl.lwjgl:lwjgl:")) {
				version = name.substring(22);
				break;
			}
		}

		reader.endArray();
		return version;
	}

	private static List<String> readManifests(Version version, JsonReader reader) throws IOException, ParseException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseException("Versions manifests must be in an array", reader);
		}

		List<String> manifests = new ArrayList<>();

		reader.beginArray();

		while (reader.hasNext()) {
			if (reader.peek() != JsonToken.BEGIN_OBJECT) {
				throw new ParseException("Version manifest entries must all be objects", reader);
			}

			reader.beginObject();

			while (reader.hasNext()) {
				String key = reader.nextName();

				switch (key) {
				case "url":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseException("Version url must be a string", reader);
					}

					manifests.add(reader.nextString());
					break;
				default:
					reader.skipValue();
				}
			}

			reader.endObject();
		}

		reader.endArray();

		return Collections.unmodifiableList(manifests);
	}

	private VersionManifest(Version latestRelease, Version latestSnapshot, Map<String, Version> versions) {
		this.latestRelease = latestRelease;
		this.latestSnapshot = latestSnapshot;
		this.versions = versions;
	}

	@Nullable
	public Version getVersion(String id) {
		return this.versions.get(id);
	}

	public Version latestRelease() {
		return this.latestRelease;
	}

	public Version latestSnapshot() {
		return this.latestSnapshot;
	}

	@Override
	public Iterator<Version> iterator() {
		return Collections.unmodifiableMap(this.versions).values().iterator();
	}

	public static final class Version {
		/**
		 * Refers to the human readable form of the version.
		 *
		 * <p>Examples: "21w05b, 1.16.5, 20w14infinite"
		 */
		private final String id;
		/**
		 * Refers to the release type of a version.
		 * This can have one of the following values:
		 * <ul>
		 * <li>snapshot
		 * <li>release
		 * <li>old_beta
		 * <li>old_alpha
		 * </ul>
		 */
		private final String type;
		/**
		 * The URL to the launcher manifest of the version.
		 */
		private final String url;
		private final String time;
		private final String releaseTime;
		private final String detailsUrl;

		private VersionDetails details;

		Version(String id, String type, String url, String time, String releaseTime, String details) {
			this.id = id;
			this.type = type;
			this.url = url;
			this.time = time;
			this.releaseTime = releaseTime;
			this.detailsUrl = details;
		}

		public String id() {
			return this.id;
		}

		public String id(GameSide side) {
			return this.details().sharedMappings() ? this.id : (this.id + "-" + side.id());
		}

		public String type() {
			return this.type;
		}

		public String url() {
			return this.url;
		}

		public String time() {
			return this.time;
		}

		public String releaseTime() {
			return this.releaseTime;
		}

		public VersionDetails details() {
			if (this.details == null) {
				this.details = readDetails(this);
			}

			return this.details;
		}
	}

	public static final class VersionDetails {
		private final Version version;
		private final List<String> manifests;
		private final boolean sharedMappings;
		private final String lwjglVersion;

		VersionDetails(Version version, List<String> manifests, String lwjglVersion, boolean sharedMappings) {
			this.version = version;
			this.manifests = manifests;
			this.lwjglVersion = lwjglVersion;
			this.sharedMappings = sharedMappings;
		}

		public Version version() {
			return this.version;
		}

		public List<String> manifests() {
			return this.manifests;
		}

		public String getLwjglVersion() {
			return lwjglVersion;
		}

		public boolean sharedMappings() {
			return this.sharedMappings;
		}
	}
}
