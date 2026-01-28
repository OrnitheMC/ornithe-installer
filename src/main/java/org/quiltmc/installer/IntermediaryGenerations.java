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

import java.util.HashSet;
import java.util.Set;

import org.quiltmc.installer.OrnitheMeta.Endpoint;

public class IntermediaryGenerations {

	static {
		try {
			Endpoint<int[]> endpoint = OrnitheMeta.intermediaryGenerationsEndpoint();
			OrnitheMeta meta = OrnitheMeta.create(OrnitheMeta.ORNITHE_META_URL, new HashSet<Endpoint<?>>(){{add(endpoint);}}).get();
			int[] gens = meta.getEndpoint(endpoint);

			latest = gens[0];
			stable = gens[1];
		} catch (Exception e) {
			throw new RuntimeException("unable to fetch latest and stable intermediary generations", e);
		}
	}

	private static int latest;
	private static int stable;

	public static int latest() {
		return latest;
	}

	public static int stable() {
		return stable;
	}
}
