package uk.co.flax.luwak.presearcher;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import uk.co.flax.luwak.analysis.TermsEnumTokenStream;
import uk.co.flax.luwak.termextractor.querytree.QueryTree;
import uk.co.flax.luwak.termextractor.weights.TermWeightor;
import uk.co.flax.luwak.util.CollectionUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/*
 * Copyright (c) 2014 Lemur Consulting Ltd.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * A TermFilteredPresearcher that indexes queries multiple times, with terms collected
 * from different routes through a querytree.  Each route will produce a set of terms
 * that are *sufficient* to select the query, and are indexed into a separate, suffixed field.
 *
 * Incoming InputDocuments are then converted to a set of Disjunction queries over each
 * suffixed field, and these queries are combined into a conjunction query, such that the
 * document's set of terms must match a term from each route.
 *
 * This allows filtering out of documents that contain one half of a two-term phrase query, for
 * example.  The query {@code "hello world"} will be indexed twice, once under 'hello' and once
 * under 'world'.  A document containing the terms "hello there" would match the first field,
 * but not the second, and so would not be selected for matching.
 *
 * The number of passes the presearcher makes is configurable.  More passes will improve the
 * selected/matched ratio, but will take longer to index and will use more RAM.
 *
 * A minimum weight can we set for terms to be chosen for the second and subsequent passes.  This
 * allows users to avoid indexing stopwords, for example.
 */
public class MultipassTermFilteredPresearcher extends TermFilteredPresearcher {

    private final int passes;
    private final float minWeight;

    /**
     * Construct a new MultipassTermFilteredPresearcher
     * @param passes the number of times a query should be indexed
     * @param minWeight the minimum weight a querytree should be advanced over
     * @param weightor the TreeWeightor to use
     * @param components optional PresearcherComponents
     */
    public MultipassTermFilteredPresearcher(int passes, float minWeight, TermWeightor weightor, PresearcherComponent... components) {
        super(weightor, components);
        this.passes = passes;
        this.minWeight = minWeight;
    }

    /**
     * Construct a new MultipassTermFilteredPresearcher using {@link TermFilteredPresearcher#DEFAULT_WEIGHTOR}
     *
     * Note that this will be constructed with a minimum advance weight of zero
     *
     * @param passes        the number of times a query should be indexed
     * @param components    optional PresearcherComponents
     */
    public MultipassTermFilteredPresearcher(int passes, PresearcherComponent... components) {
        this(passes, 0, TermFilteredPresearcher.DEFAULT_WEIGHTOR, components);
    }

    @Override
    protected DocumentQueryBuilder getQueryBuilder() {
        return new MultipassDocumentQueryBuilder();
    }

    static String field(String field, int pass) {
        return field + "_" + pass;
    }

    private class MultipassDocumentQueryBuilder implements DocumentQueryBuilder {

        BooleanQuery.Builder[] queries = new BooleanQuery.Builder[passes];
        Map<String, BytesRefHash> terms = new HashMap<>();

        public MultipassDocumentQueryBuilder() {
            for (int i = 0; i < queries.length; i++) {
                queries[i] = new BooleanQuery.Builder();
            }
        }

        @Override
        public void addTerm(String field, BytesRef term) throws IOException {
            BytesRefHash t = terms.computeIfAbsent(field, f -> new BytesRefHash());
            t.add(term);
        }

        @Override
        public Query build() {
            Map<String, BytesRef[]> collectedTerms = new HashMap<>();
            for (String field : terms.keySet()) {
                collectedTerms.put(field, CollectionUtils.convertHash(terms.get(field)));
            }
            BooleanQuery.Builder parent = new BooleanQuery.Builder();
            for (int i = 0; i < passes; i++) {
                BooleanQuery.Builder child = new BooleanQuery.Builder();
                for (String field : terms.keySet()) {
                    child.add(new TermInSetQuery(field(field, i), collectedTerms.get(field)), BooleanClause.Occur.SHOULD);
                }
                parent.add(child.build(), BooleanClause.Occur.MUST);
            }
            return parent.build();
        }
    }

    @Override
    public Document buildQueryDocument(QueryTree querytree) {

        Document doc = new Document();

        for (int i = 0; i < passes; i++) {
            Map<String, BytesRefHash> fieldTerms = collectTerms(querytree);
            debug(querytree, fieldTerms);
            for (Map.Entry<String, BytesRefHash> entry : fieldTerms.entrySet()) {
                // we add the index terms once under a suffixed field for the multipass query, and
                // once under the plan field name for the TermsEnumTokenFilter
                doc.add(new Field(field(entry.getKey(), i),
                        new TermsEnumTokenStream(new BytesRefHashIterator(entry.getValue())), QUERYFIELDTYPE));
                doc.add(new Field(entry.getKey(),
                        new TermsEnumTokenStream(new BytesRefHashIterator(entry.getValue())), QUERYFIELDTYPE));
            }
            querytree.advancePhase(minWeight);
        }

        return doc;
    }

    /**
     * Override to debug queryindexing
     * @param tree the current QueryTree
     * @param terms the terms collected from it
     */
    protected void debug(QueryTree tree, Map<String, BytesRefHash> terms) {}

}
