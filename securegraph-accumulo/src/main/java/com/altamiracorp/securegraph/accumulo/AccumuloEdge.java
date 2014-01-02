package com.altamiracorp.securegraph.accumulo;

import com.altamiracorp.securegraph.*;
import org.apache.hadoop.io.Text;

public class AccumuloEdge extends AccumuloElement implements Edge {
    public static final Text CF_SIGNAL = new Text("E");
    public static final Text CF_OUT_VERTEX = new Text("EOUT");
    public static final Text CF_IN_VERTEX = new Text("EIN");
    public static final String ROW_KEY_PREFIX = "E";
    public static final String AFTER_ROW_KEY_PREFIX = "F";
    private final Object outVertexId;
    private final Object inVertexId;
    private final String label;

    AccumuloEdge(Graph graph, Object id, Object outVertexId, Object inVertexId, String label, Visibility visibility, Property[] properties) {
        super(graph, id, visibility, properties);
        this.outVertexId = outVertexId;
        this.inVertexId = inVertexId;
        this.label = label;
    }

    @Override
    public String getLabel() {
        return label;
    }

    @Override
    public Object getOutVertexId() {
        return outVertexId;
    }

    @Override
    public Vertex getOutVertex(Authorizations authorizations) {
        return getGraph().getVertex(getOutVertexId(), authorizations);
    }

    @Override
    public Object getInVertexId() {
        return inVertexId;
    }

    @Override
    public Vertex getInVertex(Authorizations authorizations) {
        return getGraph().getVertex(getInVertexId(), authorizations);
    }
}
