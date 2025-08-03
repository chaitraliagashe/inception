/*
 * Licensed to the Technische Universität Darmstadt under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The Technische Universität Darmstadt 
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
 *  
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.annotation.layer.span.export;

import static java.util.Comparator.comparing;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.output.CloseShieldOutputStream;
import org.apache.uima.jcas.tcas.Annotation;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.core.JsonFactory;

import de.tudarmstadt.ukp.clarin.webanno.api.casstorage.session.CasStorageSession;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.LinkMode;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.inception.annotation.layer.span.SpanLayerSupport;
import de.tudarmstadt.ukp.inception.documents.api.DocumentService;
import de.tudarmstadt.ukp.inception.documents.api.export.CrossDocumentExporter_ImplBase;
import de.tudarmstadt.ukp.inception.schema.api.AnnotationSchemaService;

public class SpanLayerToJsonExporter
    extends CrossDocumentExporter_ImplBase
{
    private final AnnotationSchemaService schemaService;

    public SpanLayerToJsonExporter(AnnotationSchemaService aSchemaService,
            DocumentService aDocumentService)
    {
        super(aDocumentService);
        schemaService = aSchemaService;
    }

    @Override
    public boolean accepts(AnnotationLayer aLayer, AnnotationFeature aFeature)
    {
        if (aFeature != null && aFeature.getLinkMode() != LinkMode.NONE) {
            return false;
        }

        return SpanLayerSupport.TYPE.equals(aLayer.getType());
    }

    @Override
    public void export(OutputStream aOut, AnnotationLayer aLayer, AnnotationFeature aFeature,
            Map<SourceDocument, List<AnnotationDocument>> allAnnDocs, List<String> aAnnotators)
        throws IOException
    {
        var docs = allAnnDocs.keySet().stream() //
                .sorted(comparing(SourceDocument::getName)) //
                .toList();

        var featureName = aFeature != null ? aFeature.getName() : null;

        var jsonFactory = new JsonFactory();

        var adapter = schemaService.getAdapter(aLayer);

        try (var jg = jsonFactory.createGenerator(CloseShieldOutputStream.wrap(aOut))) {
            jg.useDefaultPrettyPrinter();

            jg.writeStartArray();

            for (var doc : docs) {
                var annDocs = allAnnDocs.get(doc);
                try (var session = CasStorageSession.openNested()) {
                    for (var dataOwner : aAnnotators) {
                        var cas = loadCasOrInitialCas(doc, dataOwner, annDocs);
                        if (cas.getTypeSystem().getType(adapter.getAnnotationTypeName()) == null) {
                            // If the types are not defined, then we do not need to try and render
                            // them because the CAS does not contain any instances of them
                            continue;
                        }

                        for (var ann : cas.<Annotation> select(adapter.getAnnotationTypeName())) {
                            jg.writeStartObject();
                            jg.writeStringField("doc", doc.getName());
                            jg.writeStringField("user", dataOwner);
                            jg.writeNumberField("begin", ann.getBegin());
                            jg.writeNumberField("end", ann.getEnd());
                            jg.writeStringField("text", ann.getCoveredText());
                            if (featureName != null) {
                                var label = adapter.renderFeatureValue(ann, featureName);
                                jg.writeStringField("label", label);
                            }
                            jg.writeEndObject();
                        }

                        jg.flush();
                    }
                }
            }

            jg.writeEndArray();
            jg.flush();
        }
    }

    @Override
    public String getFileExtension()
    {
        return EXT_JSON;
    }

    @Override
    public MediaType getMediaType()
    {
        return MediaType.APPLICATION_JSON;
    }
}
