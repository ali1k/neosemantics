package semantics;

import static semantics.RDFImport.RELATIONSHIP;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.logging.Log;

/**
 * Created by jbarrasa on 09/11/2016.
 */

class DirectStatementDeleter extends RDFToLPGStatementProcessor implements Callable<Integer> {

  public static final Label RESOURCE = Label.label("Resource");
  public static final String[] EMPTY_ARRAY = new String[0];
  Cache<String, Node> nodeCache;
  private String bNodeInfo;
  private int notDeletedStatementCount;

  public DirectStatementDeleter(GraphDatabaseService db, long batchSize, long nodeCacheSize,
      int handleUrls, int handleMultivals, Set<String> multivalPropUriList,
      boolean keepCustomDataTypes,
      Set<String> customDataTypedPropList, Set<String> predicateExclusionList,
      boolean typesToLabels, boolean klt,
      String languageFilter, boolean applyNeo4jNaming, Log l) {

    super(db, languageFilter, handleUrls, handleMultivals, multivalPropUriList, keepCustomDataTypes,
        customDataTypedPropList, predicateExclusionList, klt,
        typesToLabels, applyNeo4jNaming, batchSize);
    nodeCache = CacheBuilder.newBuilder()
        .maximumSize(nodeCacheSize)
        .build();
    log = l;
    bNodeInfo = "";
    notDeletedStatementCount = 0;
  }

  @Override
  public void endRDF() throws RDFHandlerException {
    Util.inTx(graphdb, this);
    totalTriplesMapped += mappedTripleCounter;
    if (handleUris == 0) {
      addNamespaceNode();
    }

    log.info("Successful (last) partial commit of " + mappedTripleCounter + " triples. " +
        "Total number of triples deleted is " + totalTriplesMapped + " out of "
        + totalTriplesParsed + " parsed.");
  }

  private void addNamespaceNode() {
    Map<String, Object> params = new HashMap<>();
    params.put("props", namespaces);
    graphdb.execute("MERGE (n:NamespacePrefixDefinition) SET n+={props}", params);
  }

  public Map<String, String> getNamespaces() {
    return namespaces;
  }

  // Stolen from APOC :)
  private Object toPropertyValue(Object value) {
    if (value instanceof Iterable) {
      Iterable it = (Iterable) value;
      Object first = Iterables.firstOrNull(it);
      if (first == null) {
        return EMPTY_ARRAY;
      }
      return Iterables.asArray(first.getClass(), it);
    }
    return value;
  }

  @Override
  public Integer call() throws Exception {
    int count = 0;
    for (Map.Entry<String, Set<String>> entry : resourceLabels.entrySet()) {
      if (entry.getKey().startsWith("genid")) {
        notDeletedStatementCount++;
        continue;
      }
      Node tempNode = null;
      final Node node;
      try {
        tempNode = nodeCache.get(entry.getKey(), new Callable<Node>() {
          @Override
          public Node call() {
            Node node = graphdb.findNode(RESOURCE, "uri", entry.getKey());
            if (node != null) {
              return node;
            } else {
              return node;
            }
          }
        });
      } catch (InvalidCacheLoadException icle) {
        System.err.println(icle.getMessage());
      }
      node = tempNode;
      //Can't delete node if it doesn't exist
      if (node == null) {
        notDeletedStatementCount++;
      }
      entry.getValue().forEach(l -> {
        if (node != null && node.hasLabel(Label.label(l))) {
          node.removeLabel(Label.label(l));
        } else {
          notDeletedStatementCount++;
        }
      });
      resourceProps.get(entry.getKey()).forEach((k, v) -> {
        if (v instanceof List) {
          List valuesToDelete = (List) v;
          if (node == null) {
            notDeletedStatementCount += valuesToDelete.size();
            return;
          }
          ArrayList<Object> newProps = new ArrayList<>();
          Object prop = node.getProperty(k);
          if (prop instanceof long[]) {
            long[] props = (long[]) prop;
            for (long currentVal : props) {
              if (!valuesToDelete.contains(currentVal)) {
                newProps.add(currentVal);
              }
            }
          } else if (prop instanceof double[]) {
            double[] props = (double[]) prop;
            for (double currentVal : props) {
              if (!valuesToDelete.contains(currentVal)) {
                newProps.add(currentVal);
              }
            }
          } else if (prop instanceof boolean[]) {
            boolean[] props = (boolean[]) prop;
            for (boolean currentVal : props) {
              if (!valuesToDelete.contains(currentVal)) {
                newProps.add(currentVal);
              }
            }
          } else {
            Object[] props = (Object[]) prop;
            for (Object currentVal : props) {
              if (!valuesToDelete.contains(currentVal)) {
                newProps.add(currentVal);
              }
            }
          }
          node.removeProperty(k);
          if (!newProps.isEmpty()) {
            node.setProperty(k, toPropertyValue(newProps));
          }
        } else {
          if (node == null) {
            notDeletedStatementCount++;
            return;
          }
          node.removeProperty(k);
        }
      });
    }

    for (Statement st : statements) {
      if (st.getSubject() instanceof BNode || st.getObject() instanceof BNode) {
        notDeletedStatementCount++;
        continue;
      }
      Node fromNode = null;
      try {
        fromNode = nodeCache.get(st.getSubject().stringValue(), new Callable<Node>() {
          @Override
          public Node call() {  //throws AnyException
            return graphdb.findNode(RESOURCE, "uri", st.getSubject().stringValue());
          }
        });
      } catch (InvalidCacheLoadException icle) {
        System.err.println(icle.getMessage());
      }
      if (fromNode == null) {
        continue;
      }
      Node toNode = null;
      try {
        toNode = nodeCache.get(st.getObject().stringValue(), new Callable<Node>() {
          @Override
          public Node call() {  //throws AnyException
            return graphdb.findNode(RESOURCE, "uri", st.getObject().stringValue());
          }
        });
      } catch (InvalidCacheLoadException icle) {
        System.err.println(icle.getMessage());
      }
      if (toNode == null) {
        continue;
      }
      // find relationship if it exists
      if (fromNode.getDegree(RelationshipType.withName(handleIRI(st.getPredicate(), RELATIONSHIP)),
          Direction.OUTGOING) <
          toNode.getDegree(RelationshipType.withName(handleIRI(st.getPredicate(), RELATIONSHIP)),
              Direction.INCOMING)) {
        for (Relationship rel : fromNode
            .getRelationships(RelationshipType.withName(handleIRI(st.getPredicate(), RELATIONSHIP)),
                Direction.OUTGOING)) {
          if (rel.getEndNode().equals(toNode)) {
            rel.delete();
            break;
          }
        }
      } else {
        for (Relationship rel : toNode
            .getRelationships(RelationshipType.withName(handleIRI(st.getPredicate(), RELATIONSHIP)),
                Direction.INCOMING)) {
          if (rel.getStartNode().equals(fromNode)) {
            rel.delete();
            break;
          }
        }
      }

      if (!(fromNode.hasRelationship(Direction.OUTGOING) || fromNode
          .hasRelationship(Direction.INCOMING)) && (fromNode.getAllProperties().containsKey("uri")
          && fromNode.getAllProperties().size() == 1)) {
        fromNode.delete();
      }
      if (!(toNode.hasRelationship(Direction.OUTGOING) || toNode
          .hasRelationship(Direction.INCOMING)) && (toNode.getAllProperties().containsKey("uri")
          && toNode.getAllProperties().size() == 1)) {
        toNode.delete();
      }
    }

    statements.clear();
    resourceLabels.clear();
    resourceProps.clear();
    if (notDeletedStatementCount > 0) {
      setbNodeInfo(notDeletedStatementCount
          + " of the statements could not be deleted, due to containing a blank node.");
    }

    //TODO what to return here? number of nodes and rels?
    return 0;
  }

  public String getbNodeInfo() {
    return bNodeInfo;
  }

  public void setbNodeInfo(String bNodeInfo) {
    this.bNodeInfo = bNodeInfo;
  }

  public int getNotDeletedStatementCount() {
    return notDeletedStatementCount;
  }

  @Override
  protected Map<String, String> getPopularNamespaces() {
    //get namespaces and persist them in the db
    Map<String, String> nsList = namespaceList();
    Map<String, Object> params = new HashMap();
    params.put("namespaces", nsList);
    graphdb.execute(" CREATE (ns:NamespacePrefixDefinition) SET ns = $namespaces ", params);
    return nsList;

  }

  @Override
  protected void periodicOperation() {
    Util.inTx(graphdb, this);
    totalTriplesMapped += mappedTripleCounter;
    log.info("Successful partial commit of " + mappedTripleCounter + " triples. " +
        totalTriplesMapped + " triples deleted so far...");
    mappedTripleCounter = 0;
  }
}
