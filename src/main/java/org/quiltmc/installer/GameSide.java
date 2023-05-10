/*
 * Copyright 2023 QuiltMC
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

public enum GameSide {
	CLIENT("client", "/v3/versions/loader/%s/%s/profile/json"),
	SERVER("server", "/v3/versions/loader/%s/%s/server/json");

	private final String id;
	private final String launchJsonEndpoint;

	private GameSide(String id, String launchJsonEndpoint) {
		this.id = id;
		this.launchJsonEndpoint = launchJsonEndpoint;
	}

	public String id() {
		return this.id;
	}

	public String launchJsonEndpoint() {
		return this.launchJsonEndpoint;
	}
}
