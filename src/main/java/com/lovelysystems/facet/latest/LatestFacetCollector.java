package com.lovelysystems.facet.latest;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.index.IndexReader;
import org.elasticsearch.common.CacheRecycler;
import org.elasticsearch.common.thread.ThreadLocals;
import org.elasticsearch.common.trove.ExtTLongObjectHashMap;
import org.elasticsearch.common.trove.map.hash.TObjectIntHashMap;
import org.elasticsearch.index.cache.field.data.FieldDataCache;
import org.elasticsearch.index.field.data.FieldDataType;
import org.elasticsearch.index.field.data.NumericFieldData.LongValueInDocProc;
import org.elasticsearch.index.field.data.ints.IntFieldData;
import org.elasticsearch.index.field.data.longs.LongFieldData;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.search.facet.AbstractFacetCollector;
import org.elasticsearch.search.facet.Facet;
import org.elasticsearch.search.internal.SearchContext;

public class LatestFacetCollector extends AbstractFacetCollector {

    static ThreadLocal<ThreadLocals.CleanableValue<Deque<TObjectIntHashMap<String>>>> cache = new ThreadLocal<ThreadLocals.CleanableValue<Deque<TObjectIntHashMap<String>>>>() {
        @Override
        protected ThreadLocals.CleanableValue<Deque<TObjectIntHashMap<String>>> initialValue() {
            return new ThreadLocals.CleanableValue<Deque<TObjectIntHashMap<java.lang.String>>>(
                    new ArrayDeque<TObjectIntHashMap<String>>());
        }
    };

    private final FieldDataCache fieldDataCache;

    private final String keyFieldName;
    private final String valueFieldName;
    private final String tsFieldName;

    public static final FieldDataType keyDataType = FieldDataType.DefaultTypes.LONG;
    public static final FieldDataType valueDataType = FieldDataType.DefaultTypes.INT;
    public static final FieldDataType tsDataType = FieldDataType.DefaultTypes.LONG;

    private LongFieldData keyFieldData;

    private final Aggregator aggregator;

    private final int limit = 10;
    private int size = 10;
    private int start = 0;

    private final Set<String> names = new HashSet<String>();

    public LatestFacetCollector(String facetName, String keyField,
            String valueField, String tsField, int size, int start,
            SearchContext context) {
        super(facetName);
        this.fieldDataCache = context.fieldDataCache();
        this.size = size;
        this.start = start;

        MapperService.SmartNameFieldMappers smartMappers = context
                .mapperService().smartName(keyField);
        if (smartMappers == null || !smartMappers.hasMapper()) {
            this.keyFieldName = keyField;
        } else {
            // add type filter if there is exact doc mapper associated with it
            if (smartMappers.hasDocMapper()) {
                setFilter(context.filterCache().cache(
                        smartMappers.docMapper().typeFilter()));
            }
            this.keyFieldName = smartMappers.mapper().names().indexName();
        }

        smartMappers = context.mapperService().smartName(valueField);
        if (smartMappers == null || !smartMappers.hasMapper()) {
            this.valueFieldName = valueField;
        } else {
            // add type filter if there is exact doc mapper associated with it
            if (smartMappers.hasDocMapper()) {
                setFilter(context.filterCache().cache(
                        smartMappers.docMapper().typeFilter()));
            }
            this.valueFieldName = smartMappers.mapper().names().indexName();
        }

        smartMappers = context.mapperService().smartName(tsField);
        if (smartMappers == null || !smartMappers.hasMapper()) {
            this.tsFieldName = tsField;
        } else {
            // add type filter if there is exact doc mapper associated with it
            if (smartMappers.hasDocMapper()) {
                setFilter(context.filterCache().cache(
                        smartMappers.docMapper().typeFilter()));
            }
            this.tsFieldName = smartMappers.mapper().names().indexName();
        }
        this.aggregator = new Aggregator();
    }

    @Override
    protected void doSetNextReader(IndexReader reader, int docBase)
            throws IOException {
        keyFieldData = (LongFieldData) fieldDataCache.cache(keyDataType,
                reader, keyFieldName);
        aggregator.valueFieldData = (IntFieldData) fieldDataCache.cache(
                valueDataType, reader, valueFieldName);
        aggregator.tsFieldData = (LongFieldData) fieldDataCache.cache(
                tsDataType, reader, tsFieldName);
    }

    public static class Aggregator implements LongValueInDocProc {

        final ExtTLongObjectHashMap<InternalLatestFacet.Entry> entries = CacheRecycler
                .popLongObjectMap();

        IntFieldData valueFieldData;
        LongFieldData tsFieldData;

        @Override
        public void onValue(int docId, long key) {
            InternalLatestFacet.Entry entry = entries.get(key);
            long ts = tsFieldData.longValue(docId);
            if (entry == null || entry.ts < ts) {
                int value = valueFieldData.intValue(docId);
                if (entry == null) {
                    entry = new InternalLatestFacet.Entry(ts, value);
                    entries.put(key, entry);
                } else {
                    entry.ts = ts;
                    entry.value = value;
                }
            }
        }
    }

    @Override
    protected void doCollect(int doc) throws IOException {
        keyFieldData.forEachValueInDoc(doc, aggregator);
    }

    @Override
    public Facet facet() {
        InternalLatestFacet f = new InternalLatestFacet(facetName, size, start,
                aggregator.entries.size());
        f.insert(aggregator.entries);
        return f;
    }

}
