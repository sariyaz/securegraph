package org.securegraph.elasticsearch;

import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.deletebyquery.DeleteByQueryResponse;
import org.elasticsearch.action.deletebyquery.IndexDeleteByQueryResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.rest.RestStatus;
import org.securegraph.*;
import org.securegraph.property.StreamingPropertyValue;
import org.securegraph.query.GraphQuery;
import org.securegraph.type.GeoPoint;
import org.securegraph.util.StreamUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class ElasticSearchParentChildSearchIndex extends ElasticSearchSearchIndexBase {
    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticSearchParentChildSearchIndex.class);
    public static final String PROPERTY_TYPE = "property";
    public static final int BATCH_SIZE = 1000;

    public ElasticSearchParentChildSearchIndex(Map config) {
        super(config);
    }

    @Override
    protected void createIndex(String indexName, boolean storeSourceData) throws IOException {
        super.createIndex(indexName, storeSourceData);
        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                .startObject(PROPERTY_TYPE)
                .startObject("_parent").field("type", ELEMENT_TYPE).endObject()
                .startObject("_source").field("enabled", storeSourceData).endObject()
                .startObject("properties")
                .startObject(VISIBILITY_FIELD_NAME).field("type", "string").field("analyzer", "keyword").field("index", "not_analyzed").field("store", "true").endObject()
                .endObject()
                .endObject()
                .endObject();
        LOGGER.debug(mapping.string());
        PutMappingResponse response = getClient()
                .admin()
                .indices()
                .preparePutMapping(indexName)
                .setIgnoreConflicts(false)
                .setType(PROPERTY_TYPE)
                .setSource(mapping)
                .execute()
                .actionGet();
        LOGGER.debug(response.toString());
    }

    @Override
    protected void createIndexAddFieldsToElementType(XContentBuilder builder) throws IOException {
        super.createIndexAddFieldsToElementType(builder);
        builder.startObject(VISIBILITY_FIELD_NAME).field("type", "string").field("analyzer", "keyword").field("index", "not_analyzed").field("store", "true").endObject();
    }

    @Override
    public void removeElement(Graph graph, Element element, Authorizations authorizations) {
        String indexName = getIndexName(element);
        deleteChildDocuments(indexName, element);
        deleteParentDocument(indexName, element);
    }

    private void deleteChildDocuments(String indexName, Element element) {
        String parentId = element.getId();
        DeleteByQueryResponse response = getClient()
                .prepareDeleteByQuery(indexName)
                .setTypes(PROPERTY_TYPE)
                .setQuery(
                        QueryBuilders.termQuery("_parent", ELEMENT_TYPE + "#" + parentId)
                )
                .execute()
                .actionGet();
        if (response.status() != RestStatus.OK) {
            throw new SecureGraphException("Could not remove child elements " + element.getId() + " (status: " + response.status() + ")");
        }
        if (LOGGER.isDebugEnabled()) {
            for (IndexDeleteByQueryResponse r : response) {
                LOGGER.debug("deleted child document " + r.toString());
            }
        }
    }

    private void deleteParentDocument(String indexName, Element element) {
        String id = element.getId();
        LOGGER.debug("deleting parent document " + id);
        DeleteResponse deleteResponse = getClient().delete(
                getClient()
                        .prepareDelete(indexName, ELEMENT_TYPE, id)
                        .request()
        ).actionGet();
        if (!deleteResponse.isFound()) {
            LOGGER.warn("Could not remove element " + element.getId());
        }
    }

    @Override
    public void removeProperty(Graph graph, Element element, Property property, Authorizations authorizations) {
        String indexName = getIndexName(element);
        String id = getChildDocId(element, property);
        DeleteResponse deleteResponse = getClient().delete(
                getClient()
                        .prepareDelete(indexName, PROPERTY_TYPE, id)
                        .request()
        ).actionGet();
        if (!deleteResponse.isFound()) {
            LOGGER.warn("Could not remove property " + element.getId() + " " + property.toString());
        }
        LOGGER.debug("deleted property " + element.getId() + " " + property.toString());
    }

    @Override
    public void addElement(Graph graph, Element element, Authorizations authorizations) {
        IndexInfo indexInfo = addPropertiesToIndex(element, element.getProperties());

        try {
            BulkRequest bulkRequest = new BulkRequest();

            addElementToBulkRequest(indexInfo, bulkRequest, element, authorizations);

            doBulkRequest(bulkRequest);

            if (isAutoflush()) {
                flush();
            }
        } catch (Exception e) {
            throw new SecureGraphException("Could not add element", e);
        }

        if (isUseEdgeBoost() && element instanceof Edge) {
            Element vOut = ((Edge) element).getVertex(Direction.OUT, authorizations);
            if (vOut != null) {
                addElement(graph, vOut, authorizations);
            }
            Element vIn = ((Edge) element).getVertex(Direction.IN, authorizations);
            if (vIn != null) {
                addElement(graph, vIn, authorizations);
            }
        }
    }

    @Override
    public void addElements(Graph graph, Iterable<Element> elements, Authorizations authorizations) {
        int totalCount = 0;
        Map<IndexInfo, BulkRequestWithCount> bulkRequests = new HashMap<IndexInfo, BulkRequestWithCount>();
        for (Element element : elements) {
            String indexName = getIndexName(element);
            IndexInfo indexInfo = ensureIndexCreatedAndInitialized(indexName, isStoreSourceData());
            BulkRequestWithCount bulkRequestWithCount = bulkRequests.get(indexInfo);
            if (bulkRequestWithCount == null) {
                bulkRequestWithCount = new BulkRequestWithCount();
                bulkRequests.put(indexInfo, bulkRequestWithCount);
            }

            if (bulkRequestWithCount.getCount() >= BATCH_SIZE) {
                LOGGER.debug("adding elements... " + totalCount);
                doBulkRequest(bulkRequestWithCount.getBulkRequest());
                bulkRequestWithCount.clear();
            }
            addElementToBulkRequest(indexInfo, bulkRequestWithCount.getBulkRequest(), element, authorizations);
            bulkRequestWithCount.incrementCount();
            totalCount++;

            if (isUseEdgeBoost() && element instanceof Edge) {
                Element vOut = ((Edge) element).getVertex(Direction.OUT, authorizations);
                if (vOut != null) {
                    addElementToBulkRequest(indexInfo, bulkRequestWithCount.getBulkRequest(), vOut, authorizations);
                    bulkRequestWithCount.incrementCount();
                    totalCount++;
                }
                Element vIn = ((Edge) element).getVertex(Direction.IN, authorizations);
                if (vIn != null) {
                    addElementToBulkRequest(indexInfo, bulkRequestWithCount.getBulkRequest(), vIn, authorizations);
                    bulkRequestWithCount.incrementCount();
                    totalCount++;
                }
            }
        }
        for (BulkRequestWithCount bulkRequestWithCount : bulkRequests.values()) {
            if (bulkRequestWithCount.getCount() > 0) {
                doBulkRequest(bulkRequestWithCount.getBulkRequest());
            }
        }
        LOGGER.debug("added " + totalCount + " elements");

        if (isAutoflush()) {
            flush();
        }
    }

    private void addElementToBulkRequest(IndexInfo indexInfo, BulkRequest bulkRequest, Element element, Authorizations authorizations) {
        try {
            bulkRequest.add(getParentDocumentIndexRequest(indexInfo, element, authorizations));
            for (Property property : element.getProperties()) {
                IndexRequest propertyIndexRequest = getPropertyDocumentIndexRequest(indexInfo, element, property);
                if (propertyIndexRequest != null) {
                    bulkRequest.add(propertyIndexRequest);
                }
            }
        } catch (IOException ex) {
            throw new SecureGraphException("Could not add element to bulk request", ex);
        }
    }

    public IndexRequest getPropertyDocumentIndexRequest(Element element, Property property) throws IOException {
        String indexName = getIndexName(element);
        IndexInfo indexInfo = ensureIndexCreatedAndInitialized(indexName, isStoreSourceData());
        return getPropertyDocumentIndexRequest(indexInfo, element, property);
    }

    private IndexRequest getPropertyDocumentIndexRequest(IndexInfo indexInfo, Element element, Property property) throws IOException {
        XContentBuilder jsonBuilder = buildJsonContentFromProperty(indexInfo, property);
        if (jsonBuilder == null) {
            return null;
        }

        String id = getChildDocId(element, property);

        //LOGGER.debug(jsonBuilder.string());
        IndexRequestBuilder builder = getClient().prepareIndex(indexInfo.getIndexName(), PROPERTY_TYPE, id);
        builder = builder.setParent(element.getId());
        builder = builder.setSource(jsonBuilder);
        return builder.request();
    }

    private String getChildDocId(Element element, Property property) {
        return element.getId() + "_" + property.getName() + "_" + property.getKey();
    }

    public IndexRequest getParentDocumentIndexRequest(Element element, Authorizations authorizations) throws IOException {
        String indexName = getIndexName(element);
        IndexInfo indexInfo = ensureIndexCreatedAndInitialized(indexName, isStoreSourceData());
        return getParentDocumentIndexRequest(indexInfo, element, authorizations);
    }

    private IndexRequest getParentDocumentIndexRequest(IndexInfo indexInfo, Element element, Authorizations authorizations) throws IOException {
        XContentBuilder jsonBuilder;
        jsonBuilder = XContentFactory.jsonBuilder()
                .startObject();

        String id = element.getId();
        if (element instanceof Vertex) {
            jsonBuilder.field(ElasticSearchSearchIndexBase.ELEMENT_TYPE_FIELD_NAME, ElasticSearchSearchIndexBase.ELEMENT_TYPE_VERTEX);
            if (isUseEdgeBoost()) {
                int inEdgeCount = ((Vertex) element).getEdgeCount(Direction.IN, authorizations);
                jsonBuilder.field(ElasticSearchSearchIndexBase.IN_EDGE_COUNT_FIELD_NAME, inEdgeCount);
                int outEdgeCount = ((Vertex) element).getEdgeCount(Direction.OUT, authorizations);
                jsonBuilder.field(ElasticSearchSearchIndexBase.OUT_EDGE_COUNT_FIELD_NAME, outEdgeCount);
            }
        } else if (element instanceof Edge) {
            jsonBuilder.field(ElasticSearchSearchIndexBase.ELEMENT_TYPE_FIELD_NAME, ElasticSearchSearchIndexBase.ELEMENT_TYPE_EDGE);
        } else {
            throw new SecureGraphException("Unexpected element type " + element.getClass().getName());
        }

        jsonBuilder.field(VISIBILITY_FIELD_NAME, element.getVisibility().getVisibilityString());

        return new IndexRequest(indexInfo.getIndexName(), ELEMENT_TYPE, id).source(jsonBuilder);
    }

    private XContentBuilder buildJsonContentFromProperty(IndexInfo indexInfo, Property property) throws IOException {
        XContentBuilder jsonBuilder;
        jsonBuilder = XContentFactory.jsonBuilder()
                .startObject();

        Object propertyValue = property.getValue();
        if (propertyValue != null && shouldIgnoreType(propertyValue.getClass())) {
            return null;
        } else if (propertyValue instanceof GeoPoint) {
            GeoPoint geoPoint = (GeoPoint) propertyValue;
            Map<String, Object> propertyValueMap = new HashMap<String, Object>();
            propertyValueMap.put("lat", geoPoint.getLatitude());
            propertyValueMap.put("lon", geoPoint.getLongitude());
            propertyValue = propertyValueMap;
        } else if (propertyValue instanceof StreamingPropertyValue) {
            StreamingPropertyValue streamingPropertyValue = (StreamingPropertyValue) propertyValue;
            if (!streamingPropertyValue.isSearchIndex()) {
                return null;
            }
            Class valueType = streamingPropertyValue.getValueType();
            if (valueType == String.class) {
                InputStream in = streamingPropertyValue.getInputStream();
                propertyValue = StreamUtils.toString(in);
            } else {
                throw new SecureGraphException("Unhandled StreamingPropertyValue type: " + valueType.getName());
            }
        } else if (propertyValue instanceof String) {
            PropertyDefinition propertyDefinition = indexInfo.getPropertyDefinitions().get(property.getName());
            if (propertyDefinition == null || propertyDefinition.getTextIndexHints().contains(TextIndexHint.EXACT_MATCH)) {
                jsonBuilder.field(property.getName() + ElasticSearchSearchIndexBase.EXACT_MATCH_PROPERTY_NAME_SUFFIX, propertyValue);
            }
            if (propertyDefinition == null || propertyDefinition.getTextIndexHints().contains(TextIndexHint.FULL_TEXT)) {
                jsonBuilder.field(property.getName(), propertyValue);
            }
        }

        if (propertyValue instanceof DateOnly) {
            propertyValue = ((DateOnly) propertyValue).getDate();
        }

        if (!(propertyValue instanceof String)) {
            jsonBuilder.field(property.getName(), propertyValue);
        }
        jsonBuilder.field(VISIBILITY_FIELD_NAME, property.getVisibility().getVisibilityString());

        return jsonBuilder;
    }

    @Override
    public GraphQuery queryGraph(Graph graph, String queryString, Authorizations authorizations) {
        return new ElasticSearchParentChildGraphQuery(getClient(), ALL_INDEX_NAME, graph, queryString, getAllPropertyDefinitions(), getInEdgeBoost(), getOutEdgeBoost(), authorizations);
    }

    @Override
    protected void addPropertyToIndex(IndexInfo indexInfo, String propertyName, Class dataType, boolean analyzed, Double boost) throws IOException {
        if (indexInfo.isPropertyDefined(propertyName)) {
            return;
        }

        if (shouldIgnoreType(dataType)) {
            return;
        }

        XContentBuilder mapping = XContentFactory.jsonBuilder()
                .startObject()
                .startObject(PROPERTY_TYPE)
                .startObject("_parent").field("type", ELEMENT_TYPE).endObject()
                .startObject("properties")
                .startObject(propertyName)
                .field("store", isStoreSourceData());

        addTypeToMapping(mapping, propertyName, dataType, analyzed, boost);

        mapping
                .endObject()
                .endObject()
                .endObject()
                .endObject();

        PutMappingResponse response = getClient()
                .admin()
                .indices()
                .preparePutMapping(indexInfo.getIndexName())
                .setIgnoreConflicts(false)
                .setType(PROPERTY_TYPE)
                .setSource(mapping)
                .execute()
                .actionGet();
        LOGGER.debug(response.toString());

        indexInfo.addPropertyDefinition(propertyName, new PropertyDefinition(propertyName, dataType, TextIndexHint.ALL));
    }

    @Override
    public boolean isEdgeBoostSupported() {
        return false;
    }

    @Override
    public SearchIndexSecurityGranularity getSearchIndexSecurityGranularity() {
        return SearchIndexSecurityGranularity.PROPERTY;
    }
}
