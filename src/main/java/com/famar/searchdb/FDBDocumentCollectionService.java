package com.famar.searchdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockFactory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.store.SimpleFSLockFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.famar.searchdb.admin.FDBCollectionsManagerService;
import com.famar.searchdb.visitors.FetchSourceFieldsVisitor;

public class FDBDocumentCollectionService{

	private Logger logger = LoggerFactory.getLogger(this.getClass());
	
	//properties
//	private IndexWriterConfig config;
	private FSDirectory directory;
	private Analyzer analyzer;
	
	//dependencies
	private FDBCollectionsManagerService collectionsManager;
	
	public FDBDocumentCollectionService(String collection) {
        try {
        	this.collectionsManager = FDBCollectionsManagerService.getInstance();
        	
        	Path location = collectionsManager.getCollectionLocation(collection);
        	
			LockFactory lockFactory = SimpleFSLockFactory.INSTANCE;
			
            this.directory = FSDirectory.open(location, lockFactory); // use lucene defaults

		} catch (IOException e) {
			logger.error("",e);
			throw new FDBException(e);
		}
        this.analyzer = new StandardAnalyzer();
//        this.config = new IndexWriterConfig(analyzer);
	}
	
	public String save(byte[] json) {
		
		FDBLuceneDocument doc;
		try {
			Map<String,Object> fieldsMap = (Map<String,Object>) new ObjectMapper().readValue(json, Map.class);
			doc = parseFieldsMap(fieldsMap);
		} catch (RuntimeException | IOException e) {
			logger.error("",e);
			throw new FDBException(e);
		}
		
		return internalSave(doc, json);
	}
	
	private String internalSave(FDBLuceneDocument luceneDocument, byte[] documentSource) {
		String uuid = prepareDocument(luceneDocument, documentSource);
        try {
        	IndexWriter writer = createWriterRetrying();
			writer.addDocument(luceneDocument);
			writer.close();
			return uuid;
		} catch (IOException e) {
			logger.error("",e);
			throw new FDBException(e);
		}
	}

	private String prepareDocument(FDBLuceneDocument luceneDocument, byte[] documentSource) {
		luceneDocument.add(new StoredField(FamarDBConstants.FIELDNAME_SOURCE, documentSource));
		
		String uuid = UUID.randomUUID().toString();
		luceneDocument.add(new StringField(FamarDBConstants.FAMARDB_UUID, uuid, Store.YES));
		return uuid;
	}
	
	public Collection<FamarDBDocument> search(String querystr) {
		Query q = parseQueryString(querystr);
		return internalSearch(q, FamarDBConstants.DEFAULT_PAGE_SIZE);
	}
	
	private Collection<FamarDBDocument> internalSearch(Query query, int pageSize) {
		IndexReader reader = getIndexReader();
		IndexSearcher searcher = new IndexSearcher(reader);
		try {
			TopDocs docs = searcher.search(query, pageSize);
			FetchSourceFieldsVisitor visitor = new FetchSourceFieldsVisitor();
			return Stream.of(docs.scoreDocs)
					.map(sc -> {
						try {
							visitor.reset();
							searcher.doc(sc.doc,visitor);
							return visitor.getDocument();
						} catch (IOException e) {
							logger.error("",e);
							throw new FDBException(e);
						}
					})
					.collect(Collectors.toList());
			
		} catch (IOException e) {
			logger.error("",e);
			throw new FDBException(e);
		} finally {
			closeReader(reader);
		}
	}
	
	public FamarDBDocument get(String uuid) {
		Query q = new TermQuery(new Term(FamarDBConstants.FAMARDB_UUID, uuid));
		IndexReader reader = getIndexReader();
		try {
			IndexSearcher searcher = new IndexSearcher(reader);
			TopDocs docs = searcher.search(q, 1);
			FetchSourceFieldsVisitor visitor = new FetchSourceFieldsVisitor();
			ScoreDoc doc = Stream.of(docs.scoreDocs).findFirst().orElse(null);
			if(doc!=null) {
				searcher.doc(doc.doc, visitor);
			    return visitor.getDocument();
			}
			return null;
		} catch (IOException e) {
			logger.error("",e);
			throw new FDBException(e);
		} finally {
			closeReader(reader);
		}
	}

	private void closeReader(IndexReader reader) {
		try {
			reader.close();
		} catch (IOException e) {
			logger.error("",e);
			throw new FDBException(e);
		}
	}

	private Query parseQueryString(String querystr) {
		Query q;
		try {
			q = new QueryParser("", analyzer).parse(querystr);
		} catch (ParseException e) {
			logger.error("",e);
			throw new FDBException(e);
		}
		return q;
	}
	
	private IndexWriter createWriterRetrying() throws IOException {
		try {
			return createIndexWriter();			
		}catch(LockObtainFailedException lofe) {
			logger.info("",lofe);
			Files.delete(this.directory.getDirectory().resolve(IndexWriter.WRITE_LOCK_NAME));
			return createIndexWriter();			
		}
	}

	private IndexWriter createIndexWriter() throws IOException {
		return new IndexWriter(directory, new IndexWriterConfig(analyzer));
	}
	
	private IndexReader getIndexReader() {
		IndexReader reader;
		try {
			reader = DirectoryReader.open(directory);
		} catch (IOException e) {
			logger.error("",e);
			throw new FDBException(e);
		}
		return reader;
	}
	
	private FDBLuceneDocument parseFieldsMap(Map<String, Object> fieldsMap) {
		return new FDBLuceneDocument(Optional.ofNullable(fieldsMap)
					.orElseThrow(FDBException::new)
					.entrySet()
					.stream()
					.flatMap(this::toField)
					.collect(Collectors.toList()));
	}
	
	private Stream<Field> toField(Entry<String, Object> entry) {
		return toField(entry.getKey(), entry.getValue());
	}
	
	private Stream<Field> toField(String name, Object value) {
		Field field = null;
		Stream.Builder<Field> fields = Stream.builder();
		if(value instanceof String) {
			field = new StringField(name, (String)value, Store.YES);
		} else if(value instanceof Integer) {
			Integer v = (Integer) value;
			fields.add(new StoredField(name, v))
				.add(new IntPoint(name, v))
				.add(new NumericDocValuesField(name, v));
			field = new StoredField(name, (Integer) value);
		} else if(value instanceof Long) {
			field = new StoredField(name, (Long) value);
			//TODO add multiple fields for numeric types like i did in integer
		} else if(value instanceof Map) {
			Map<String,Object> obj = (Map<String,Object>) value;
			return obj.entrySet().stream().map(e->new AbstractMap.SimpleEntry<>(name+"."+e.getKey(), e.getValue())).flatMap(this::toField);
		} else if(value instanceof List) {
			return Stream.empty();
//			List<Object> obj = (List<Object>) value;
//			return obj.stream().flatMap(v -> this.toField(name, v));
		}
		if(field==null) {
			throw new FDBException("incompatible field type");			
		}
		return Stream.of(field);
	}
//	
//	private Field toField(Entry<String, Object> entry) {
//		String name = entry.getKey();
//		Object value = entry.getValue();
//		Field field = null;
//		if(value instanceof String) {
//			field = new StringField(name, (String)value, Store.YES);
//		} else if(value instanceof Integer) {
//			field = new NumericDocValuesField(name, (Integer)value);
//		} else if(value instanceof Long) {
//			field = new NumericDocValuesField(name, (Long)value);
//		} else if(value instanceof Map) {
//			Map<String,Object> obj = (Map<String,Object>)value;
//			return obj.entrySet().stream().map(this::toField);//.map(this::toField);
//		}
//		if(field==null) {
//			throw new FDBException("incompatible field type");			
//		}
//	}
	
}
