package com.lovelysystems.facet.distinct;

import org.elasticsearch.common.CacheRecycler;
import org.elasticsearch.common.trove.ExtTLongObjectHashMap;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;
import org.elasticsearch.search.facet.Facet;
import org.elasticsearch.search.facet.datehistogram.InternalDateHistogramFacet;

import java.io.IOException;
import java.util.*;

/**
 */
public abstract class InternalDistinctDateHistogramFacet extends InternalDateHistogramFacet {

    public static final String TYPE = "distinct_date_histogram";

    protected String name;

    protected ComparatorType comparatorType;

    ExtTLongObjectHashMap<InternalDistinctDateHistogramFacet.DistinctEntry> tEntries;
    boolean cachedEntries;
    Collection<DistinctEntry> entries = null;

    public InternalDistinctDateHistogramFacet() {
    }

    public static void registerStreams() {
        LongInternalDistinctDateHistogramFacet.registerStreams();
        StringInternalDistinctDateHistogramFacet.registerStreams();
    }

    public InternalDistinctDateHistogramFacet(String name, ComparatorType comparatorType, ExtTLongObjectHashMap<InternalDistinctDateHistogramFacet.DistinctEntry> entries, boolean cachedEntries) {
        this.name = name;
        this.comparatorType = comparatorType;
        this.tEntries = entries;
        this.cachedEntries = cachedEntries;
        this.entries = entries.valueCollection();
    }

    /**
     * A histogram entry representing a single entry within the result of a histogram facet.
     *
     * It holds a set of distinct values and the time.
     */
    public static class DistinctEntry implements Entry {
        private final long time;
        private final Set<Object> values;

        public DistinctEntry(long time, Set<Object> values) {
            this.time = time;
            this.values = values;
        }

        public DistinctEntry(long time) {
            this.time = time;
            this.values = new HashSet<Object>();
        }

        @Override public long time() {
            return time;
        }

        @Override public long getTime() {
            return time();
        }

        public Set<Object> value() {
            return this.values;
        }

        public Set<Object> getValue() {
            return value();
        }

        @Override public long count() {
            return value().size();
        }

        @Override public long getCount() {
            return count();
        }

        @Override public long totalCount() {
            return 0;
        }

        @Override public long getTotalCount() {
            return 0;
        }

        @Override public double total() {
            return Double.NaN;
        }

        @Override public double getTotal() {
            return total();
        }

        @Override public double mean() {
            return Double.NaN;
        }

        @Override public double getMean() {
            return mean();
        }

        @Override public double min() {
            return Double.NaN;
        }

        @Override public double getMin() {
            return Double.NaN;
        }

        @Override public double max() {
            return Double.NaN;
        }

        @Override public double getMax() {
            return Double.NaN;
        }
    }


    @Override public String name() {
        return this.name;
    }

    @Override public String getName() {
        return name();
    }

    @Override public String type() {
        return TYPE;
    }

    @Override public String getType() {
        return type();
    }

    @Override public List<DistinctEntry> entries() {
        if (!(entries instanceof List)) {
            entries = new ArrayList<DistinctEntry>(entries);
        }
        return (List<DistinctEntry>) entries;
    }

    @Override public List<DistinctEntry> getEntries() {
        return entries();
    }

    @Override public Iterator<Entry> iterator() {
        return (Iterator) entries().iterator();
    }

    void releaseCache() {
        if (cachedEntries) {
            CacheRecycler.pushLongObjectMap(tEntries);
            cachedEntries = false;
            tEntries = null;
        }
    }


    static final class Fields {
        static final XContentBuilderString _TYPE = new XContentBuilderString("_type");
        static final XContentBuilderString ENTRIES = new XContentBuilderString("entries");
        static final XContentBuilderString TIME = new XContentBuilderString("time");
        static final XContentBuilderString COUNT = new XContentBuilderString("count");
        static final XContentBuilderString TOTAL_COUNT = new XContentBuilderString("count");
    }

    @Override public Facet reduce(String name, List<Facet> facets) {
        if (facets.size() == 1) {
            // we need to sort it
            InternalDistinctDateHistogramFacet internalFacet = (InternalDistinctDateHistogramFacet) facets.get(0);
            List<DistinctEntry> entries = internalFacet.entries();
            Collections.sort(entries, comparatorType.comparator());
            internalFacet.releaseCache();
            return internalFacet;
        }

        ExtTLongObjectHashMap<DistinctEntry> map = CacheRecycler.popLongObjectMap();
        for (Facet facet : facets) {
            InternalDistinctDateHistogramFacet histoFacet = (InternalDistinctDateHistogramFacet) facet;
            for (DistinctEntry fullEntry : histoFacet.entries) {
                DistinctEntry current = map.get(fullEntry.getTime());
                if (current != null) {
                    current.getValue().addAll(fullEntry.getValue());

                } else {
                    map.put(fullEntry.getTime(), fullEntry);
                }
            }
            histoFacet.releaseCache();
        }

        // sort
        Object[] values = map.internalValues();
        Arrays.sort(values, (Comparator) comparatorType.comparator());
        List<DistinctEntry> ordered = new ArrayList<DistinctEntry>(map.size());
        for (int i = 0; i < map.size(); i++) {
            DistinctEntry value = (DistinctEntry) values[i];
            if (value == null) {
                break;
            }
            ordered.add(value);
        }

        CacheRecycler.pushLongObjectMap(map);

        // just initialize it as already ordered facet
        InternalDistinctDateHistogramFacet ret = newFacet();
        ret.name = name;
        ret.comparatorType = comparatorType;
        ret.entries = ordered;
        return ret;
    }

    protected abstract InternalDistinctDateHistogramFacet newFacet();

    /**
     * Builds the final JSON result.
     *
     * For each time entry we provide the number of distinct values in the time range.
     */
    @Override public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        Set<Object> all = null;
        if (entries().size() != 1) {
            all = new HashSet<Object>();
        }
        builder.startObject(name);
        builder.field(Fields._TYPE, TYPE);
        builder.startArray(Fields.ENTRIES);
        for (DistinctEntry entry : entries) {
            builder.startObject();
            builder.field(Fields.TIME, entry.time());
            builder.field(Fields.COUNT, entry.count());
            builder.endObject();
            if (entries().size() == 1) {
                all = entry.value();
            } else {
                all.addAll(entry.value());
            }
        }
        builder.endArray();
        builder.field(Fields.TOTAL_COUNT, all.size());
        builder.endObject();
        return builder;
    }


}