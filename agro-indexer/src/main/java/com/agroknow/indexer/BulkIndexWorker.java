package com.agroknow.indexer;

import com.agroknow.domain.Akif;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.charset.Charset;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.client.Client;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

/**
 * BulkIndexWorker is used to parse a list of files and submit them to elasticsearch
 * if they were updated later than indexer's last run (if any).
 *
 * @author aggelos
 */
public class BulkIndexWorker implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(BulkIndexWorker.class);

    private List<File> files;
    private String fileFormat;
    private Charset charset;
    private long lastCheck;
    private Client esClient;
    private ObjectMapper objectMapper;

    /**
     * BulkIndexWorker is used to parse a list of files and submit them to elasticsearch
     * if they were updated later than indexer's last run (if any). Files are read
     * in UTF-8 charset.
     *
     * @param files The list of Files to process
     * @param fileFormat The data format of those files
     * @param objectMapper  The objectMapper to use to serialize/deserialize json
     * @param esClient The elasticsearch client to use for (bulk) indexing
     */
    public BulkIndexWorker(List<File> files, String fileFormat, ObjectMapper objectMapper, Client esClient) {
        this(files, fileFormat, Charset.forName("UTF-8"), objectMapper, new DateTime().withZone(DateTimeZone.UTC).getMillis(), esClient);
    }

    /**
     * BulkIndexWorker is used to parse a list of files and submit them to elasticsearch
     * if they were updated later than indexer's last run (if any)
     *
     * @param files The list of Files to process
     * @param fileFormat The data format of those files
     * @param charset The charset to read files with
     * @param objectMapper  The objectMapper to use to serialize/deserialize json
     * @param lastCheck The timestamp of indexer's last run
     * @param esClient The elasticsearch client to use for (bulk) indexing
     */
    public BulkIndexWorker(List<File> files, String fileFormat, Charset charset, ObjectMapper objectMapper, long lastCheck, Client esClient) {
        this.files = files;
        this.fileFormat = fileFormat;
        this.charset = charset;
        this.objectMapper = objectMapper;
        this.lastCheck = lastCheck;
        this.esClient = esClient;

    }

    /**
     * Create a bulk index request based on the list of files passed to the worker.
     * It also adds metrics about its progress.
     *
     * @throws Exception
     */
    public void index() throws Exception {
        Assert.notNull(this.files);
        Assert.notNull(this.fileFormat);
        Assert.notNull(this.charset);
        Assert.notNull(this.lastCheck);
        Assert.notNull(this.esClient);

        LOG.debug("START bulk indexer");
        BulkRequestBuilder bulkRequest = esClient.prepareBulk();

        // add documents to bulk request
        String source;
        Akif doc;
        for(File f : files) {
            LOG.debug("PROCESS file: {}", f.getAbsolutePath());

            // check if file changed after lastCheck
            if(this.lastCheck > f.lastModified()) {
                continue;
            }

            // if file is touched after the last check, parse it to Akif.class
            // and compare Akif.lastUpdateDate with lastCheck
            source = FileUtils.readFileToString(f, charset);
            doc = objectMapper.reader(Akif.class).readValue(source);
            if(this.lastCheck > doc.getLastUpdateDate().getTime()) {
                continue;
            }

            // create an indexRequest and add it to the bulk
            String id = doc.getIdentifier();
            IndexRequestBuilder indexRequestBuilder = esClient.prepareIndex(fileFormat, fileFormat, id)
                                                              .setSource(source);
            bulkRequest.add(indexRequestBuilder);
        }

        // read data from bulkResponse
        BulkResponse bulkResponse = bulkRequest.execute().actionGet();
        if (bulkResponse.hasFailures()) {
            for (BulkItemResponse item : bulkResponse.getItems()) {
                if (item.isFailed()) {
                    LOG.error("Document [{}] failed to get indexed", item.getId());
                    MetricsRegistryHolder.getCounter("FILES[FAILED]").inc();
                } else {
                    MetricsRegistryHolder.getCounter("FILES[INDEXED]").inc();
                }
            }
        } else {
            MetricsRegistryHolder.getCounter("FILES[INDEXED]").inc(bulkResponse.getItems().length);
        }

        LOG.debug("END bulk indexer");
    }

    public void run() {
        try {
            index();
        } catch (Exception ex) {
            LOG.error(ex.getMessage(), (LOG.isDebugEnabled() ? ex : null));
        }
    }

    public void setFiles(List<File> files) {
        this.files = files;
    }

    public void setFileFormat(String fileFormat) {
        this.fileFormat = fileFormat;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    public void setLastCheck(long lastCheck) {
        this.lastCheck = lastCheck;
    }

    public void setEsClient(Client esClient) {
        this.esClient = esClient;
    }
}
