package org.apache.maven.index.packer;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0    
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.util.Bits;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.context.DefaultIndexingContext;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexUtils;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.context.NexusIndexWriter;
import org.apache.maven.index.context.NexusLegacyAnalyzer;
import org.apache.maven.index.creator.LegacyDocumentUpdater;
import org.apache.maven.index.incremental.IncrementalHandler;
import org.apache.maven.index.updater.IndexDataWriter;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;

/**
 * A default {@link IndexPacker} implementation. Creates the properties, legacy index zip and new gz files.
 * 
 * @author Tamas Cservenak
 * @author Eugene Kuleshov
 */
@Component( role = IndexPacker.class )
public class DefaultIndexPacker
    extends AbstractLogEnabled
    implements IndexPacker
{
    @Requirement( role = IncrementalHandler.class )
    IncrementalHandler incrementalHandler;

    public void packIndex( IndexPackingRequest request )
        throws IOException, IllegalArgumentException
    {
        if ( request.getTargetDir() == null )
        {
            throw new IllegalArgumentException( "The target dir is null" );
        }

        if ( request.getTargetDir().exists() )
        {
            if ( !request.getTargetDir().isDirectory() )
            {
                throw new IllegalArgumentException( //
                    String.format( "Specified target path %s is not a directory",
                        request.getTargetDir().getAbsolutePath() ) );
            }
            if ( !request.getTargetDir().canWrite() )
            {
                throw new IllegalArgumentException( String.format( "Specified target path %s is not writtable",
                    request.getTargetDir().getAbsolutePath() ) );
            }
        }
        else
        {
            if ( !request.getTargetDir().mkdirs() )
            {
                throw new IllegalArgumentException( "Can't create " + request.getTargetDir().getAbsolutePath() );
            }
        }

        // These are all of the files we'll be dealing with (except for the incremental chunks of course)
        File legacyFile = new File( request.getTargetDir(), IndexingContext.INDEX_FILE_PREFIX + ".zip" );
        File v1File = new File( request.getTargetDir(), IndexingContext.INDEX_FILE_PREFIX + ".gz" );

        Properties info = null;

        try
        {
            // Note that for incremental indexes to work properly, a valid index.properties file
            // must be present
            info = readIndexProperties( request );

            if ( request.isCreateIncrementalChunks() )
            {
                List<Integer> chunk = incrementalHandler.getIncrementalUpdates( request, info );

                if ( chunk == null )
                {
                    getLogger().debug( "Problem with Chunks, forcing regeneration of whole index" );
                    incrementalHandler.initializeProperties( info );
                }
                else if ( chunk.isEmpty() )
                {
                    getLogger().debug( "No incremental changes, not writing new incremental chunk" );
                }
                else
                {
                    File file =
                        new File( request.getTargetDir(), //
                            IndexingContext.INDEX_FILE_PREFIX + "."
                                + info.getProperty( IndexingContext.INDEX_CHUNK_COUNTER ) + ".gz" );

                    writeIndexData( request.getContext(), //
                        chunk, file );

                    if ( request.isCreateChecksumFiles() )
                    {
                        FileUtils.fileWrite(
                            new File( file.getParentFile(), file.getName() + ".sha1" ).getAbsolutePath(),
                            DigesterUtils.getSha1Digest( file ) );

                        FileUtils.fileWrite(
                            new File( file.getParentFile(), file.getName() + ".md5" ).getAbsolutePath(),
                            DigesterUtils.getMd5Digest( file ) );
                    }
                }
            }
        }
        catch ( IOException e )
        {
            getLogger().info( "Unable to read properties file, will force index regeneration" );
            info = new Properties();
            incrementalHandler.initializeProperties( info );
        }

        Date timestamp = request.getContext().getTimestamp();

        if ( timestamp == null )
        {
            timestamp = new Date( 0 ); // never updated
        }

        if ( request.getFormats().contains( IndexPackingRequest.IndexFormat.FORMAT_LEGACY ) )
        {
            info.setProperty( IndexingContext.INDEX_LEGACY_TIMESTAMP, format( timestamp ) );

            writeIndexArchive( request.getContext(), legacyFile, request.getMaxIndexChunks() );

            if ( request.isCreateChecksumFiles() )
            {
                FileUtils.fileWrite(
                    new File( legacyFile.getParentFile(), legacyFile.getName() + ".sha1" ).getAbsolutePath(),
                    DigesterUtils.getSha1Digest( legacyFile ) );

                FileUtils.fileWrite(
                    new File( legacyFile.getParentFile(), legacyFile.getName() + ".md5" ).getAbsolutePath(),
                    DigesterUtils.getMd5Digest( legacyFile ) );
            }
        }

        if ( request.getFormats().contains( IndexPackingRequest.IndexFormat.FORMAT_V1 ) )
        {
            info.setProperty( IndexingContext.INDEX_TIMESTAMP, format( timestamp ) );

            writeIndexData( request.getContext(), null, v1File );

            if ( request.isCreateChecksumFiles() )
            {
                FileUtils.fileWrite( new File( v1File.getParentFile(), v1File.getName() + ".sha1" ).getAbsolutePath(),
                    DigesterUtils.getSha1Digest( v1File ) );

                FileUtils.fileWrite( new File( v1File.getParentFile(), v1File.getName() + ".md5" ).getAbsolutePath(),
                    DigesterUtils.getMd5Digest( v1File ) );
            }
        }

        writeIndexProperties( request, info );
    }

    private Properties readIndexProperties( IndexPackingRequest request )
        throws IOException
    {
        File file = null;

        if ( request.isUseTargetProperties() )
        {
            file = new File( request.getTargetDir(), IndexingContext.INDEX_REMOTE_PROPERTIES_FILE );
        }
        else
        {
            file =
                new File( request.getContext().getIndexDirectoryFile(), IndexingContext.INDEX_PACKER_PROPERTIES_FILE );
        }

        Properties properties = new Properties();

        FileInputStream fos = null;

        try
        {
            fos = new FileInputStream( file );
            properties.load( fos );
        }
        finally
        {
            if ( fos != null )
            {
                fos.close();
            }
        }

        return properties;
    }

    void writeIndexArchive( IndexingContext context, File targetArchive )
        throws IOException
    {
        writeIndexArchive(context, targetArchive, IndexPackingRequest.MAX_CHUNKS);
    }
    
    void writeIndexArchive( IndexingContext context, File targetArchive, int maxSegments )
        throws IOException
    {
        if ( targetArchive.exists() )
        {
            targetArchive.delete();
        }

        OutputStream os = null;

        try
        {
            os = new BufferedOutputStream( new FileOutputStream( targetArchive ), 4096 );

            packIndexArchive( context, os );
        }
        finally
        {
            IOUtil.close( os );
        }
    }

    /**
     * Pack legacy index archive into a specified output stream
     */
    public static void packIndexArchive( IndexingContext context, OutputStream os )
        throws IOException
    {
        packIndexArchive(context, os, IndexPackingRequest.MAX_CHUNKS);
    }
    
    /**
     * Pack legacy index archive into a specified output stream
     */
    public static void packIndexArchive( IndexingContext context, OutputStream os, int maxSegments )
        throws IOException
    {
        File indexArchive = File.createTempFile( "nexus-index", "" );

        File indexDir = new File( indexArchive.getAbsoluteFile().getParentFile(), indexArchive.getName() + ".dir" );

        indexDir.mkdirs();

        FSDirectory fdir = FSDirectory.open( indexDir );

        try
        {
            // force the timestamp update
            IndexUtils.updateTimestamp( context.getIndexDirectory(), context.getTimestamp() );
            IndexUtils.updateTimestamp( fdir, context.getTimestamp() );

            final IndexSearcher indexSearcher = context.acquireIndexSearcher();
            try
            {
                copyLegacyDocuments( indexSearcher.getIndexReader(), fdir, context, maxSegments);
            }
            finally
            {
                context.releaseIndexSearcher( indexSearcher );
            }
            packDirectory( fdir, os );
        }
        finally
        {
            IndexUtils.close( fdir );
            indexArchive.delete();
            IndexUtils.delete( indexDir );
        }
    }

    static void copyLegacyDocuments( IndexReader r, Directory targetdir, IndexingContext context )
        throws CorruptIndexException, LockObtainFailedException, IOException
    {
        copyLegacyDocuments(r, targetdir, context, IndexPackingRequest.MAX_CHUNKS);
    }
    
    static void copyLegacyDocuments( IndexReader r, Directory targetdir, IndexingContext context, int maxSegments)
        throws CorruptIndexException, LockObtainFailedException, IOException
    {
        IndexWriter w = null;
        Bits liveDocs = MultiFields.getLiveDocs(r);
        try
        {
            w = new NexusIndexWriter( targetdir, new NexusLegacyAnalyzer(), true );

            for ( int i = 0; i < r.maxDoc(); i++ )
            {
                if ( liveDocs == null || liveDocs.get(i) )
                {
                    Document legacyDocument = r.document( i );
                    Document updatedLegacyDocument = updateLegacyDocument( legacyDocument, context );
                    
                    //Lucene does not return metadata for stored documents, so we need to fix that
                    for (IndexableField indexableField : updatedLegacyDocument.getFields())
                    {
                        if(indexableField.name().equals(DefaultIndexingContext.FLD_DESCRIPTOR))
                        {
                            updatedLegacyDocument = new Document();
                            updatedLegacyDocument.add(new StringField(DefaultIndexingContext.FLD_DESCRIPTOR, DefaultIndexingContext.FLD_DESCRIPTOR_CONTENTS, Field.Store.YES));
                            updatedLegacyDocument.add( new StringField( DefaultIndexingContext.FLD_IDXINFO, DefaultIndexingContext.VERSION + ArtifactInfo.FS + context.getRepositoryId(), Field.Store.YES) );
                            break;
                        }
                    }
                    
                    w.addDocument( updatedLegacyDocument );
                }
            }

            w.forceMerge(maxSegments);
            w.commit();
        }
        finally
        {
            IndexUtils.close( w );
        }
    }

    static Document updateLegacyDocument( Document doc, IndexingContext context )
    {
        ArtifactInfo ai = IndexUtils.constructArtifactInfo( doc, context );
        if ( ai == null )
        {
            return doc;
        }

        Document document = new Document();
        document.add( new Field( ArtifactInfo.UINFO, ai.getUinfo(), Field.Store.YES, Field.Index.NOT_ANALYZED ) );

        for ( IndexCreator ic : context.getIndexCreators() )
        {
            if ( ic instanceof LegacyDocumentUpdater )
            {
                ( (LegacyDocumentUpdater) ic ).updateLegacyDocument( ai, document );
            }
        }

        return document;
    }

    static void packDirectory( Directory directory, OutputStream os )
        throws IOException
    {
        ZipOutputStream zos = null;
        try
        {
            zos = new ZipOutputStream( os );
            zos.setLevel( 9 );

            String[] names = directory.listAll();

            boolean savedTimestamp = false;

            byte[] buf = new byte[8192];

            for ( int i = 0; i < names.length; i++ )
            {
                String name = names[i];

                writeFile( name, zos, directory, buf );

                if ( name.equals( IndexUtils.TIMESTAMP_FILE ) )
                {
                    savedTimestamp = true;
                }
            }

            // FSDirectory filter out the foreign files
            if ( !savedTimestamp && directory.fileExists( IndexUtils.TIMESTAMP_FILE ) )
            {
                writeFile( IndexUtils.TIMESTAMP_FILE, zos, directory, buf );
            }
        }
        finally
        {
            IndexUtils.close( zos );
        }
    }

    static void writeFile( String name, ZipOutputStream zos, Directory directory, byte[] buf )
        throws IOException
    {
        ZipEntry e = new ZipEntry( name );

        zos.putNextEntry( e );

        IndexInput in = directory.openInput( name, IOContext.DEFAULT );

        try
        {
            int toRead = 0;

            int bytesLeft = (int) in.length();

            while ( bytesLeft > 0 )
            {
                toRead = ( bytesLeft >= buf.length ) ? buf.length : bytesLeft;
                bytesLeft -= toRead;

                in.readBytes( buf, 0, toRead, false );

                zos.write( buf, 0, toRead );
            }
        }
        finally
        {
            IndexUtils.close( in );
        }

        zos.flush();

        zos.closeEntry();
    }

    void writeIndexData( IndexingContext context, List<Integer> docIndexes, File targetArchive )
        throws IOException
    {
        if ( targetArchive.exists() )
        {
            targetArchive.delete();
        }

        OutputStream os = null;

        try
        {
            os = new FileOutputStream( targetArchive );

            IndexDataWriter dw = new IndexDataWriter( os );
            dw.write( context, docIndexes );

            os.flush();
        }
        finally
        {
            IOUtil.close( os );
        }
    }

    void writeIndexProperties( IndexPackingRequest request, Properties info )
        throws IOException
    {
        File propertyFile =
            new File( request.getContext().getIndexDirectoryFile(), IndexingContext.INDEX_PACKER_PROPERTIES_FILE );
        File targetPropertyFile = new File( request.getTargetDir(), IndexingContext.INDEX_REMOTE_PROPERTIES_FILE );

        info.setProperty( IndexingContext.INDEX_ID, request.getContext().getId() );

        OutputStream os = null;

        try
        {
            os = new FileOutputStream( propertyFile );

            info.store( os, null );
        }
        finally
        {
            IOUtil.close( os );
        }

        try
        {
            os = new FileOutputStream( targetPropertyFile );

            info.store( os, null );
        }
        finally
        {
            IOUtil.close( os );
        }

        if ( request.isCreateChecksumFiles() )
        {
            FileUtils.fileWrite(
                new File( targetPropertyFile.getParentFile(), targetPropertyFile.getName() + ".sha1" ).getAbsolutePath(),
                DigesterUtils.getSha1Digest( targetPropertyFile ) );

            FileUtils.fileWrite(
                new File( targetPropertyFile.getParentFile(), targetPropertyFile.getName() + ".md5" ).getAbsolutePath(),
                DigesterUtils.getMd5Digest( targetPropertyFile ) );
        }
    }

    private String format( Date d )
    {
        SimpleDateFormat df = new SimpleDateFormat( IndexingContext.INDEX_TIME_FORMAT );
        df.setTimeZone( TimeZone.getTimeZone( "GMT" ) );
        return df.format( d );
    }
}
