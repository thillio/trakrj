/**
 * Copyright (c) 2019 Eric Thill
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */
package io.thill.trakrj.trackers;

import io.thill.trakrj.Record;
import io.thill.trakrj.Stat;
import io.thill.trakrj.Stat.StatType;
import io.thill.trakrj.Tracker;
import io.thill.trakrj.function.DoubleObjectConsumer;
import org.eclipse.collections.api.map.primitive.MutableDoubleObjectMap;
import org.eclipse.collections.impl.factory.primitive.DoubleObjectMaps;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracker to keep double:Object values in a map. Reset clears the map.
 *
 * @author Eric Thill
 */
public class DoubleObjectMapTracker implements Tracker {

	private final MutableDoubleObjectMap<Object> map = DoubleObjectMaps.mutable.empty();

	@Override
	public void record(Record record) {
		map.put(record.getKeyDouble(), record.getValueObject());
	}

	@Override
	public void reset() {
		map.clear();
	}

	@Override
	public String toString() {
		return map.toString();
	}

	/**
	 * Iterate over all elements in the underlying map. The purpose of providing access to the underlying map as a function is to not expose the underlying data
	 * structure types as part of the API.
	 *
	 * @param c The consumer to accept all values in the map
	 */
	public void forEach(DoubleObjectConsumer<Object> c) {
		map.forEachKeyValue((double k, Object v) -> c.accept(k, v));
	}

	@Override
	public List<? extends Stat> stats() {
		final List<Stat> stats = new ArrayList<>();
		forEach((k,v) -> stats.add(new SettableStat(Double.toString(k), StatType.OBJECT).setObjectValue(v)));
		return stats;
	}
}
