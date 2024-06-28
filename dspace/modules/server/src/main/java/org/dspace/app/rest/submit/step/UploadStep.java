/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.submit.step;

import static com.itextpdf.layout.properties.TextAlignment.CENTER;
import static com.itextpdf.layout.properties.VerticalAlignment.TOP;
import static java.lang.Math.PI;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.ErrorRest;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.model.step.DataUpload;
import org.dspace.app.rest.model.step.UploadBitstreamRest;
import org.dspace.app.rest.repository.WorkspaceItemRestRepository;
import org.dspace.app.rest.submit.AbstractProcessingStep;
import org.dspace.app.rest.submit.SubmissionService;
import org.dspace.app.rest.submit.UploadableStep;
import org.dspace.app.rest.submit.factory.PatchOperationFactory;
import org.dspace.app.rest.submit.factory.impl.PatchOperation;
import org.dspace.app.rest.utils.Utils;
import org.dspace.app.util.SubmissionStepConfig;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Bundle;
import org.dspace.content.InProgressSubmission;
import org.dspace.content.Item;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.springframework.web.multipart.MultipartFile;

import com.itextpdf.io.image.ImageData;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.EncryptionConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.WriterProperties;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;

/**
 * Upload step for DSpace Spring Rest. Expose information about the bitstream
 * uploaded for the in progress submission.
 *
 * @author Luigi Andrea Pascarelli (luigiandrea.pascarelli at 4science.it)
 */
public class UploadStep extends AbstractProcessingStep
        implements UploadableStep {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(UploadStep.class);

    @Override
    public DataUpload getData(SubmissionService submissionService, InProgressSubmission obj,
            SubmissionStepConfig config) throws Exception {

        DataUpload result = new DataUpload();
        List<Bundle> bundles = itemService.getBundles(obj.getItem(), Constants.CONTENT_BUNDLE_NAME);
        for (Bundle bundle : bundles) {
            for (Bitstream source : bundle.getBitstreams()) {
                UploadBitstreamRest b = submissionService.buildUploadBitstream(configurationService, source);
                result.getFiles().add(b);
            }
        }
        return result;
    }

    @Override
    public void doPatchProcessing(Context context, HttpServletRequest currentRequest, InProgressSubmission source,
            Operation op, SubmissionStepConfig stepConf) throws Exception {

        String instance = null;
        if ("remove".equals(op.getOp())) {
            if (op.getPath().contains(UPLOAD_STEP_METADATA_PATH)) {
                instance = UPLOAD_STEP_METADATA_OPERATION_ENTRY;
            } else if (op.getPath().contains(UPLOAD_STEP_ACCESSCONDITIONS_OPERATION_ENTRY)) {
                instance = stepConf.getType() + "." + UPLOAD_STEP_ACCESSCONDITIONS_OPERATION_ENTRY;
            } else {
                instance = UPLOAD_STEP_REMOVE_OPERATION_ENTRY;
            }
        } else if ("move".equals(op.getOp())) {
            if (op.getPath().contains(UPLOAD_STEP_METADATA_PATH)) {
                instance = UPLOAD_STEP_METADATA_OPERATION_ENTRY;
            } else {
                instance = UPLOAD_STEP_MOVE_OPERATION_ENTRY;
            }
        } else {
            if (op.getPath().contains(UPLOAD_STEP_ACCESSCONDITIONS_OPERATION_ENTRY)) {
                instance = stepConf.getType() + "." + UPLOAD_STEP_ACCESSCONDITIONS_OPERATION_ENTRY;
            } else if (op.getPath().contains(UPLOAD_STEP_METADATA_PATH)) {
                instance = UPLOAD_STEP_METADATA_OPERATION_ENTRY;
            }
        }
        if (instance == null) {
            throw new UnprocessableEntityException("The path " + op.getPath() + " is not supported by the operation "
                    + op.getOp());
        }
        PatchOperation<?> patchOperation = new PatchOperationFactory().instanceOf(instance, op.getOp());
        patchOperation.perform(context, currentRequest, source, op);
    }

    public com.itextpdf.layout.element.Paragraph createWatermarkParagraph() throws IOException {
        String imagePath = configurationService.getProperty("dspace.dir") + "/config/fonts/image.png";
         ImageData imgData = ImageDataFactory.create(imagePath);
        Image image = new Image(imgData);
        image.setWidth(500f);
        com.itextpdf.layout.element.Paragraph p = new Paragraph();
        p.add(image);
        return p;
    }

    public void addWatermarkToPage(Document document, int pageIndex, com.itextpdf.layout.element.Paragraph paragraph,
            float verticalOffset) {
        PdfPage pdfPage = document.getPdfDocument()
                .getPage(pageIndex);
        PageSize pageSize = (PageSize) pdfPage.getPageSizeWithRotation();

        float x = (pageSize.getLeft() + pageSize.getRight()) / 2;
        float y = (pageSize.getTop() + pageSize.getBottom()) / 2;
        float xOffset = 100f / 2;
        float rotationInRadians = (float) (PI / 180 * 45f);
        document.showTextAligned(paragraph, x - xOffset, y + verticalOffset, pageIndex, CENTER, TOP, rotationInRadians);
    }

    public void addWatermarkToExistingPage(Document document, int pageIndex,
            com.itextpdf.layout.element.Paragraph paragraph, PdfExtGState graphicState, float verticalOffset) {
        try {
            PdfDocument pdfDoc = document.getPdfDocument();
            PdfPage pdfPage = pdfDoc.getPage(pageIndex);
            PageSize pageSize = (PageSize) pdfPage.getPageSizeWithRotation();

            float x = (pageSize.getLeft() + pageSize.getRight()) / 2;
            float y = (pageSize.getTop() + pageSize.getBottom()) / 2;
            PdfCanvas over = new PdfCanvas(pdfDoc.getPage(pageIndex));
            over.saveState();
            over.setExtGState(graphicState);
            float xOffset = 100f / 2;
            float rotationInRadians = (float) (PI / 180 * 45f);
            document.showTextAligned(paragraph, x - xOffset, y + verticalOffset, pageIndex, CENTER, TOP,
                    rotationInRadians);
            document.flush();
            over.restoreState();
            over.release();
        } catch (Exception e) {
            log.error(e);
        }

    }

    @Override
    public ErrorRest upload(Context context, SubmissionService submissionService, SubmissionStepConfig stepConfig,
            InProgressSubmission wsi, MultipartFile file) {

        Bitstream source = null;
        BitstreamFormat bf = null;

        Item item = wsi.getItem();
        List<Bundle> bundles = null;
        try {
            // do we already have a bundle?
            bundles = itemService.getBundles(item, Constants.CONTENT_BUNDLE_NAME);

            String outputPdf = configurationService.getProperty("dspace.dir") + "/upload/watermarked_"
                    + file.getOriginalFilename();
            
            WriterProperties writerProperties = new WriterProperties().setStandardEncryption(new byte[0], new byte[0], 0, EncryptionConstants.ENCRYPTION_AES_256); 
            try (

                    PdfDocument pdfDocument = new PdfDocument(
                            new PdfReader(file.getInputStream()),
                            new PdfWriter(outputPdf, writerProperties)
                            )) {
                Document document = new Document(pdfDocument);

                com.itextpdf.layout.element.Paragraph paragraph = createWatermarkParagraph();
                PdfExtGState transparentGraphicState = new PdfExtGState().setFillOpacity(0.5f);
                for (int i = 0; i <= document.getPdfDocument()
                        .getNumberOfPages(); i++) {
                    addWatermarkToExistingPage(document, i, paragraph, transparentGraphicState, 0f);
                }
            }

            InputStream inputStream = new BufferedInputStream(new BufferedInputStream(new FileInputStream(outputPdf)));
            if (bundles.size() < 1) {
                // set bundle's name to ORIGINAL
                source = itemService.createSingleBitstream(context, inputStream, item, Constants.CONTENT_BUNDLE_NAME);
            } else {
                // we have a bundle already, just add bitstream
                source = bitstreamService.create(context, bundles.get(0), inputStream);
            }

            source.setName(context, Utils.getFileName(file));
            source.setSource(context, file.getOriginalFilename());

            // Identify the format
            bf = bitstreamFormatService.guessFormat(context, source);
            source.setFormat(context, bf);

            // Update to DB
            bitstreamService.update(context, source);
            itemService.update(context, item); 

        } catch (Exception e) {
            log.error(e.getMessage(), e);
            ErrorRest result = new ErrorRest();
            result.setMessage(e.getMessage());
            if (bundles != null && bundles.size() > 0) {
                result.getPaths().add(
                        "/" + WorkspaceItemRestRepository.OPERATION_PATH_SECTIONS + "/" + stepConfig.getId() + "/files/"
                                +
                                bundles.get(0).getBitstreams().size());
            } else {
                result.getPaths()
                        .add("/" + WorkspaceItemRestRepository.OPERATION_PATH_SECTIONS + "/" + stepConfig.getId());
            }
            return result;
        }

        return null;
    }
}
