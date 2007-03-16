/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.query.lucene;

import org.apache.jackrabbit.core.ItemManager;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.NodeIdIterator;
import org.apache.jackrabbit.core.query.AbstractQueryHandler;
import org.apache.jackrabbit.core.query.ExecutableQuery;
import org.apache.jackrabbit.core.query.QueryHandlerContext;
import org.apache.jackrabbit.core.query.QueryHandler;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.NodeStateIterator;
import org.apache.jackrabbit.extractor.DefaultTextExtractor;
import org.apache.jackrabbit.extractor.TextExtractor;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.NameFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.search.Hits;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.document.Document;
import org.apache.commons.collections.iterators.AbstractIteratorDecorator;

import javax.jcr.RepositoryException;
import javax.jcr.query.InvalidQueryException;
import java.io.IOException;
import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;

/**
 * Implements a {@link org.apache.jackrabbit.core.query.QueryHandler} using
 * Lucene.
 */
public class SearchIndex extends AbstractQueryHandler {

    /** The logger instance for this class */
    private static final Logger log = LoggerFactory.getLogger(SearchIndex.class);

    /**
     * Name of the file to persist search internal namespace mappings.
     */
    private static final String NS_MAPPING_FILE = "ns_mappings.properties";

    /**
     * The default value for property {@link #minMergeDocs}.
     */
    public static final int DEFAULT_MIN_MERGE_DOCS = 100;

    /**
     * The default value for property {@link #maxMergeDocs}.
     */
    public static final int DEFAULT_MAX_MERGE_DOCS = 100000;

    /**
     * the default value for property {@link #mergeFactor}.
     */
    public static final int DEFAULT_MERGE_FACTOR = 10;

    /**
     * the default value for property {@link #maxFieldLength}.
     */
    public static final int DEFAULT_MAX_FIELD_LENGTH = 10000;

    /**
     * The default value for property {@link #extractorPoolSize}.
     */
    public static final int DEFAULT_EXTRACTOR_POOL_SIZE = 0;

    /**
     * The default value for property {@link #extractorBackLog}.
     */
    public static final int DEFAULT_EXTRACTOR_BACK_LOG = 100;

    /**
     * The default timeout in milliseconds which is granted to the text
     * extraction process until fulltext indexing is deferred to a background
     * thread.
     */
    public static final long DEFAULT_EXTRACTOR_TIMEOUT = 100;

    /**
     * The actual index
     */
    private MultiIndex index;

    /**
     * The analyzer we use for indexing.
     */
    private Analyzer analyzer;

    /**
     * List of text extractor and text filter class names. The configured
     * classes will be instantiated and used to extract text content from
     * binary properties.
     */
    private String textFilterClasses =
        DefaultTextExtractor.class.getName();

    /**
     * Text extractor for extracting text content of binary properties.
     */
    private TextExtractor extractor;

    /**
     * The location of the search index.
     * <p/>
     * Note: This is a <b>mandatory</b> parameter!
     */
    private String path;

    /**
     * minMergeDocs config parameter.
     */
    private int minMergeDocs = DEFAULT_MIN_MERGE_DOCS;

    /**
     * volatileIdleTime config parameter.
     */
    private int volatileIdleTime = 3;

    /**
     * maxMergeDocs config parameter
     */
    private int maxMergeDocs = DEFAULT_MAX_MERGE_DOCS;

    /**
     * mergeFactor config parameter
     */
    private int mergeFactor = DEFAULT_MERGE_FACTOR;

    /**
     * maxFieldLength config parameter
     */
    private int maxFieldLength = DEFAULT_MAX_FIELD_LENGTH;

    /**
     * extractorPoolSize config parameter
     */
    private int extractorPoolSize = DEFAULT_EXTRACTOR_POOL_SIZE;

    /**
     * extractorBackLog config parameter
     */
    private int extractorBackLog = DEFAULT_EXTRACTOR_BACK_LOG;

    /**
     * extractorTimeout config parameter
     */
    private long extractorTimeout = DEFAULT_EXTRACTOR_TIMEOUT;

    /**
     * Number of documents that are buffered before they are added to the index.
     */
    private int bufferSize = 10;

    /**
     * Compound file flag
     */
    private boolean useCompoundFile = true;

    /**
     * Flag indicating whether document order is enable as the default ordering.
     */
    private boolean documentOrder = true;

    /**
     * If set <code>true</code> the index is checked for consistency on startup.
     * If <code>false</code> a consistency check is only performed when there
     * are entries in the redo log on startup.
     * <p/>
     * Default value is: <code>false</code>.
     */
    private boolean forceConsistencyCheck = false;

    /**
     * If set <code>true</code> errors detected by the consistency check are
     * repaired. If <code>false</code> the errors are only reported in the log.
     * <p/>
     * Default value is: <code>true</code>.
     */
    private boolean autoRepair = true;

    /**
     * The uuid resolver cache size.
     * <p/>
     * Default value is: <code>1000</code>.
     */
    private int cacheSize = 1000;

    /**
     * The number of documents that are pre fetched when a query is executed.
     * <p/>
     * Default value is: {@link Integer#MAX_VALUE}.
     */
    private int resultFetchSize = Integer.MAX_VALUE;

    /**
     * Indicates if this <code>SearchIndex</code> is closed and cannot be used
     * anymore.
     */
    private boolean closed = false;

    /**
     * Default constructor.
     */
    public SearchIndex() {
        this.analyzer = new StandardAnalyzer(new String[]{});
    }

    /**
     * Initializes this <code>QueryHandler</code>. This implementation requires
     * that a path parameter is set in the configuration. If this condition
     * is not met, a <code>IOException</code> is thrown.
     *
     * @throws IOException if an error occurs while initializing this handler.
     */
    protected void doInit() throws IOException {
        QueryHandlerContext context = getContext();
        if (path == null) {
            throw new IOException("SearchIndex requires 'path' parameter in configuration!");
        }

        Set excludedIDs = new HashSet();
        if (context.getExcludedNodeId() != null) {
            excludedIDs.add(context.getExcludedNodeId());
        }

        extractor = createTextExtractor();

        File indexDir = new File(path);

        NamespaceMappings nsMappings;
        if (context.getParentHandler() instanceof SearchIndex) {
            // use system namespace mappings
            SearchIndex sysIndex = (SearchIndex) context.getParentHandler();
            nsMappings = sysIndex.getNamespaceMappings();
        } else {
            // read local namespace mappings
            File mapFile = new File(indexDir, NS_MAPPING_FILE);
            if (mapFile.exists()) {
                // be backward compatible and use ns_mappings.properties from
                // index folder
                nsMappings = new FileBasedNamespaceMappings(mapFile);
            } else {
                // otherwise use repository wide stable index prefix from
                // namespace registry
                nsMappings = new NSRegistryBasedNamespaceMappings(
                        context.getNamespaceRegistry());
            }
        }

        index = new MultiIndex(indexDir, this, context.getItemStateManager(),
                context.getRootId(), excludedIDs, nsMappings);
        if (index.getRedoLogApplied() || forceConsistencyCheck) {
            log.info("Running consistency check...");
            try {
                ConsistencyCheck check = ConsistencyCheck.run(index,
                        context.getItemStateManager());
                if (autoRepair) {
                    check.repair(true);
                } else {
                    List errors = check.getErrors();
                    if (errors.size() == 0) {
                        log.info("No errors detected.");
                    }
                    for (Iterator it = errors.iterator(); it.hasNext();) {
                        ConsistencyCheckError err = (ConsistencyCheckError) it.next();
                        log.info(err.toString());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to run consistency check on index: " + e);
            }
        }
        log.info("Index initialized: " + path);
    }

    /**
     * Adds the <code>node</code> to the search index.
     * @param node the node to add.
     * @throws RepositoryException if an error occurs while indexing the node.
     * @throws IOException if an error occurs while adding the node to the index.
     */
    public void addNode(NodeState node) throws RepositoryException, IOException {
        throw new UnsupportedOperationException("addNode");
    }

    /**
     * Removes the node with <code>uuid</code> from the search index.
     * @param id the id of the node to remove from the index.
     * @throws IOException if an error occurs while removing the node from
     * the index.
     */
    public void deleteNode(NodeId id) throws IOException {
        throw new UnsupportedOperationException("deleteNode");
    }

    /**
     * This implementation forwards the call to
     * {@link MultiIndex#update(java.util.Iterator, java.util.Iterator)} and
     * transforms the two iterators to the required types.
     *
     * @param remove uuids of nodes to remove.
     * @param add    NodeStates to add. Calls to <code>next()</code> on this
     *               iterator may return <code>null</code>, to indicate that a
     *               node could not be indexed successfully.
     * @throws RepositoryException if an error occurs while indexing a node.
     * @throws IOException         if an error occurs while updating the index.
     */
    public void updateNodes(NodeIdIterator remove, NodeStateIterator add)
            throws RepositoryException, IOException {
        checkOpen();
        index.update(new AbstractIteratorDecorator(remove) {
            public Object next() {
                return ((NodeId) super.next()).getUUID();
            }
        }, new AbstractIteratorDecorator(add) {
            public Object next() {
                NodeState state = (NodeState) super.next();
                if (state == null) {
                    return null;
                }
                Document doc = null;
                try {
                    doc = createDocument(state, getNamespaceMappings());
                } catch (RepositoryException e) {
                    log.error("Exception while creating document for node: "
                            + state.getNodeId() + ": " + e.toString());
                }
                return doc;
            }
        });
    }

    /**
     * Creates a new query by specifying the query statement itself and the
     * language in which the query is stated.  If the query statement is
     * syntactically invalid, given the language specified, an
     * InvalidQueryException is thrown. <code>language</code> must specify a query language
     * string from among those returned by QueryManager.getSupportedQueryLanguages(); if it is not
     * then an <code>InvalidQueryException</code> is thrown.
     *
     * @param session the session of the current user creating the query object.
     * @param itemMgr the item manager of the current user.
     * @param statement the query statement.
     * @param language the syntax of the query statement.
     * @throws InvalidQueryException if statement is invalid or language is unsupported.
     * @return A <code>Query</code> object.
     */
    public ExecutableQuery createExecutableQuery(SessionImpl session,
                                             ItemManager itemMgr,
                                             String statement,
                                             String language)
            throws InvalidQueryException {
        QueryImpl query = new QueryImpl(session, itemMgr, this,
                getContext().getPropertyTypeRegistry(), statement, language);
        query.setRespectDocumentOrder(documentOrder);
        return query;
    }

    /**
     * Closes this <code>QueryHandler</code> and frees resources attached
     * to this handler.
     */
    public void close() {
        // shutdown extractor
        if (extractor instanceof PooledTextExtractor) {
            ((PooledTextExtractor) extractor).shutdown();
        }
        index.close();
        getContext().destroy();
        closed = true;
        log.info("Index closed: " + path);
    }

    /**
     * Executes the query on the search index.
     * @param queryImpl the query impl.
     * @param query the lucene query.
     * @param orderProps name of the properties for sort order.
     * @param orderSpecs the order specs for the sort order properties.
     * <code>true</code> indicates ascending order, <code>false</code> indicates
     * descending.
     * @return the lucene Hits object.
     * @throws IOException if an error occurs while searching the index.
     */
    public QueryHits executeQuery(QueryImpl queryImpl,
                                  Query query,
                                  QName[] orderProps,
                                  boolean[] orderSpecs) throws IOException {
        checkOpen();
        QueryHandler parentHandler = getContext().getParentHandler();
        IndexReader parentReader = null;
        if (parentHandler instanceof SearchIndex) {
            parentReader = ((SearchIndex) parentHandler).index.getIndexReader();
        }

        SortField[] sortFields = createSortFields(orderProps, orderSpecs);

        IndexReader reader = index.getIndexReader();
        if (parentReader != null) {
            // todo FIXME not type safe
            CachingMultiReader[] readers = {(CachingMultiReader) reader,
                                            (CachingMultiReader) parentReader};
            reader = new CombinedIndexReader(readers);
        }

        IndexSearcher searcher = new IndexSearcher(reader);
        Hits hits;
        if (sortFields.length > 0) {
            hits = searcher.search(query, new Sort(sortFields));
        } else {
            hits = searcher.search(query);
        }
        return new QueryHits(hits, reader);
    }

    /**
     * Returns the analyzer in use for indexing.
     * @return the analyzer in use for indexing.
     */
    public Analyzer getTextAnalyzer() {
        return analyzer;
    }

    /**
     * Returns the text extractor in use for indexing.
     *
     * @return the text extractor in use for indexing.
     */
    public TextExtractor getTextExtractor() {
        return extractor;
    }

    /**
     * Returns the namespace mappings for the internal representation.
     * @return the namespace mappings for the internal representation.
     */
    public NamespaceMappings getNamespaceMappings() {
        return index.getNamespaceMappings();
    }

    /**
     * Creates the SortFields for the order properties.
     *
     * @param orderProps the order properties.
     * @param orderSpecs the order specs for the properties.
     * @return an array of sort fields
     */
    protected SortField[] createSortFields(QName[] orderProps,
                                           boolean[] orderSpecs) {
        List sortFields = new ArrayList();
        for (int i = 0; i < orderProps.length; i++) {
            String prop = null;
            if (QName.JCR_SCORE.equals(orderProps[i])) {
                // order on jcr:score does not use the natural order as
                // implemented in lucene. score ascending in lucene means that
                // higher scores are first. JCR specs that lower score values
                // are first.
                sortFields.add(new SortField(null, SortField.SCORE, orderSpecs[i]));
            } else {
                try {
                    prop = NameFormat.format(orderProps[i], getNamespaceMappings());
                } catch (NoPrefixDeclaredException e) {
                    // will never happen
                }
                sortFields.add(new SortField(prop, SharedFieldSortComparator.PROPERTIES, !orderSpecs[i]));
            }
        }
        return (SortField[]) sortFields.toArray(new SortField[sortFields.size()]);
    }

    /**
     * Creates a lucene <code>Document</code> for a node state using the
     * namespace mappings <code>nsMappings</code>.
     *
     * @param node       the node state to index.
     * @param nsMappings the namespace mappings of the search index.
     * @return a lucene <code>Document</code> that contains all properties of
     *         <code>node</code>.
     * @throws RepositoryException if an error occurs while indexing the
     *                             <code>node</code>.
     */
    protected Document createDocument(NodeState node,
                                      NamespaceMappings nsMappings)
            throws RepositoryException {
        return new NodeIndexer(node, getContext().getItemStateManager(),
                nsMappings, extractor).createDoc();
    }

    /**
     * Returns the actual index.
     *
     * @return the actual index.
     */
    protected MultiIndex getIndex() {
        return index;
    }

    /**
     * Factory method to create the <code>TextExtractor</code> instance.
     *
     * @return the <code>TextExtractor</code> instance this index should use.
     */
    protected TextExtractor createTextExtractor() {
        TextExtractor txtExtr = new JackrabbitTextExtractor(textFilterClasses);
        if (extractorPoolSize > 0) {
            // wrap with pool
            txtExtr = new PooledTextExtractor(txtExtr, extractorPoolSize,
                    extractorBackLog, extractorTimeout);
        }
        return txtExtr;
    }

    //----------------------------< internal >----------------------------------

    /**
     * Combines multiple {@link CachingMultiReader} into a <code>MultiReader</code>
     * with {@link HierarchyResolver} support.
     */
    protected static final class CombinedIndexReader
            extends MultiReader
            implements HierarchyResolver, MultiIndexReader {

        /**
         * The sub readers.
         */
        final private CachingMultiReader[] subReaders;

        /**
         * Doc number starts for each sub reader
         */
        private int[] starts;

        public CombinedIndexReader(CachingMultiReader[] indexReaders) throws IOException {
            super(indexReaders);
            this.subReaders = indexReaders;
            this.starts = new int[subReaders.length + 1];

            int maxDoc = 0;
            for (int i = 0; i < subReaders.length; i++) {
                starts[i] = maxDoc;
                maxDoc += subReaders[i].maxDoc();
            }
            starts[subReaders.length] = maxDoc;
        }

        /**
         * @inheritDoc
         */
        public int getParent(int n) throws IOException {
            int i = readerIndex(n);
            DocId id = subReaders[i].getParentDocId(n - starts[i]);
            id = id.applyOffset(starts[i]);
            return id.getDocumentNumber(this);
        }

        //-------------------------< MultiIndexReader >-------------------------

        /**
         * {@inheritDoc}
         */
        public IndexReader[] getIndexReaders() {
            IndexReader readers[] = new IndexReader[subReaders.length];
            System.arraycopy(subReaders, 0, readers, 0, subReaders.length);
            return readers;
        }

        //---------------------------< internal >-------------------------------

        /**
         * Returns the reader index for document <code>n</code>.
         * Implementation copied from lucene MultiReader class.
         *
         * @param n document number.
         * @return the reader index.
         */
        private int readerIndex(int n) {
            int lo = 0;                                      // search starts array
            int hi = subReaders.length - 1;                  // for first element less

            while (hi >= lo) {
                int mid = (lo + hi) >> 1;
                int midValue = starts[mid];
                if (n < midValue) {
                    hi = mid - 1;
                } else if (n > midValue) {
                    lo = mid + 1;
                } else {                                      // found a match
                    while (mid + 1 < subReaders.length && starts[mid + 1] == midValue) {
                        mid++;                                  // scan to last match
                    }
                    return mid;
                }
            }
            return hi;
        }

        public boolean equals(Object obj) {
            if (obj instanceof CombinedIndexReader) {
                CombinedIndexReader other = (CombinedIndexReader) obj;
                return Arrays.equals(subReaders, other.subReaders);
            }
            return false;
        }

        public int hashCode() {
            int hash = 0;
            for (int i = 0; i < subReaders.length; i++) {
                hash = 31 * hash + subReaders[i].hashCode();
            }
            return hash;
        }
    }

    //--------------------------< properties >----------------------------------

    /**
     * Sets the analyzer in use for indexing. The given analyzer class name
     * must satisfy the following conditions:
     * <ul>
     *   <li>the class must exist in the class path</li>
     *   <li>the class must have a public default constructor</li>
     *   <li>the class must be a Lucene Analyzer</li>
     * </ul>
     * <p>
     * If the above conditions are met, then a new instance of the class is
     * set as the analyzer. Otherwise a warning is logged and the current
     * analyzer is not changed.
     * <p>
     * This property setter method is normally invoked by the Jackrabbit
     * configuration mechanism if the "analyzer" parameter is set in the
     * search configuration.
     *
     * @param analyzerClassName the analyzer class name
     */
    public void setAnalyzer(String analyzerClassName) {
        try {
            Class analyzerClass = Class.forName(analyzerClassName);
            analyzer = (Analyzer) analyzerClass.newInstance();
        } catch (Exception e) {
            log.warn("Invalid Analyzer class: " + analyzerClassName, e);
        }
    }

    /**
     * Returns the class name of the analyzer that is currently in use.
     *
     * @return class name of analyzer in use.
     */
    public String getAnalyzer() {
        return analyzer.getClass().getName();
    }

    /**
     * Sets the location of the search index.
     *
     * @param path the location of the search index.
     */
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Returns the location of the search index. Returns <code>null</code> if
     * not set.
     *
     * @return the location of the search index.
     */
    public String getPath() {
        return path;
    }

    /**
     * The lucene index writer property: useCompoundFile
     */
    public void setUseCompoundFile(boolean b) {
        useCompoundFile = b;
    }

    /**
     * Returns the current value for useCompoundFile.
     *
     * @return the current value for useCompoundFile.
     */
    public boolean getUseCompoundFile() {
        return useCompoundFile;
    }

    /**
     * The lucene index writer property: minMergeDocs
     */
    public void setMinMergeDocs(int minMergeDocs) {
        this.minMergeDocs = minMergeDocs;
    }

    /**
     * Returns the current value for minMergeDocs.
     *
     * @return the current value for minMergeDocs.
     */
    public int getMinMergeDocs() {
        return minMergeDocs;
    }

    /**
     * Sets the property: volatileIdleTime
     *
     * @param volatileIdleTime idle time in seconds
     */
    public void setVolatileIdleTime(int volatileIdleTime) {
        this.volatileIdleTime = volatileIdleTime;
    }

    /**
     * Returns the current value for volatileIdleTime.
     *
     * @return the current value for volatileIdleTime.
     */
    public int getVolatileIdleTime() {
        return volatileIdleTime;
    }

    /**
     * The lucene index writer property: maxMergeDocs
     */
    public void setMaxMergeDocs(int maxMergeDocs) {
        this.maxMergeDocs = maxMergeDocs;
    }

    /**
     * Returns the current value for maxMergeDocs.
     *
     * @return the current value for maxMergeDocs.
     */
    public int getMaxMergeDocs() {
        return maxMergeDocs;
    }

    /**
     * The lucene index writer property: mergeFactor
     */
    public void setMergeFactor(int mergeFactor) {
        this.mergeFactor = mergeFactor;
    }

    /**
     * Returns the current value for the merge factor.
     *
     * @return the current value for the merge factor.
     */
    public int getMergeFactor() {
        return mergeFactor;
    }

    /**
     * @see VolatileIndex#setBufferSize(int)
     */
    public void setBufferSize(int size) {
        bufferSize = size;
    }

    /**
     * Returns the current value for the buffer size.
     *
     * @return the current value for the buffer size.
     */
    public int getBufferSize() {
        return bufferSize;
    }

    public void setRespectDocumentOrder(boolean docOrder) {
        documentOrder = docOrder;
    }

    public boolean getRespectDocumentOrder() {
        return documentOrder;
    }

    public void setForceConsistencyCheck(boolean b) {
        forceConsistencyCheck = b;
    }

    public boolean getForceConsistencyCheck() {
        return forceConsistencyCheck;
    }

    public void setAutoRepair(boolean b) {
        autoRepair = b;
    }

    public boolean getAutoRepair() {
        return autoRepair;
    }

    public void setCacheSize(int size) {
        cacheSize = size;
    }

    public int getCacheSize() {
        return cacheSize;
    }

    public void setMaxFieldLength(int length) {
        maxFieldLength = length;
    }

    public int getMaxFieldLength() {
        return maxFieldLength;
    }

    /**
     * Sets the list of text extractors (and text filters) to use for
     * extracting text content from binary properties. The list must be
     * comma (or whitespace) separated, and contain fully qualified class
     * names of the {@link TextExtractor} (and {@link org.apache.jackrabbit.core.query.TextFilter}) classes
     * to be used. The configured classes must all have a public default
     * constructor.
     *
     * @param filterClasses comma separated list of class names
     */
    public void setTextFilterClasses(String filterClasses) {
        this.textFilterClasses = filterClasses;
    }

    /**
     * Returns the fully qualified class names of the text filter instances
     * currently in use. The names are comma separated.
     *
     * @return class names of the text filters in use.
     */
    public String getTextFilterClasses() {
        return textFilterClasses;
    }

    /**
     * Tells the query handler how many result should be fetched initially when
     * a query is executed.
     *
     * @param size the number of results to fetch initially.
     */
    public void setResultFetchSize(int size) {
        resultFetchSize = size;
    }

    /**
     * @return the number of results the query handler will fetch initially when
     *         a query is executed.
     */
    public int getResultFetchSize() {
        return resultFetchSize;
    }

    /**
     * The number of background threads for the extractor pool.
     *
     * @param numThreads the number of threads.
     */
    public void setExtractorPoolSize(int numThreads) {
        if (numThreads < 0) {
            numThreads = 0;
        }
        extractorPoolSize = numThreads;
    }

    /**
     * @return the size of the thread pool which is used to run the text
     *         extractors when binary content is indexed.
     */
    public int getExtractorPoolSize() {
        return extractorPoolSize;
    }

    /**
     * The number of extractor jobs that are queued until a new job is executed
     * with the current thread instead of using the thread pool.
     *
     * @param backLog size of the extractor job queue.
     */
    public void setExtractorBackLogSize(int backLog) {
        extractorBackLog = backLog;
    }

    /**
     * @return the size of the extractor queue back log.
     */
    public int getExtractorBackLogSize() {
        return extractorBackLog;
    }

    /**
     * The timeout in milliseconds which is granted to the text extraction
     * process until fulltext indexing is deferred to a background thread.
     *
     * @param timeout the timeout in milliseconds.
     */
    public void setExtractorTimeout(long timeout) {
        extractorTimeout = timeout;
    }

    /**
     * @return the extractor timeout in milliseconds.
     */
    public long getExtractorTimeout() {
        return extractorTimeout;
    }

    //----------------------------< internal >----------------------------------

    /**
     * Checks if this <code>SearchIndex</code> is open, otherwise throws
     * an <code>IOException</code>.
     *
     * @throws IOException if this <code>SearchIndex</code> had been closed.
     */
    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("query handler closed and cannot be used anymore.");
}
    }
}
