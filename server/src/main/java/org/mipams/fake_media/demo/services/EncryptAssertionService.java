package org.mipams.fake_media.demo.services;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Collection;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;

import org.mipams.fake_media.entities.ProvenanceErrorMessages;
import org.mipams.fake_media.entities.ProvenanceMetadata;
import org.mipams.fake_media.entities.assertions.RedactableAssertion;
import org.mipams.fake_media.services.AssertionFactory;
import org.mipams.jumbf.core.entities.BinaryDataBox;
import org.mipams.jumbf.core.entities.JsonBox;
import org.mipams.jumbf.core.entities.JumbfBox;
import org.mipams.jumbf.core.entities.JumbfBoxBuilder;
import org.mipams.jumbf.core.services.CoreParserService;
import org.mipams.jumbf.core.services.boxes.JumbfBoxService;
import org.mipams.jumbf.core.services.content_types.JsonContentType;
import org.mipams.jumbf.core.util.CoreUtils;
import org.mipams.jumbf.core.util.MipamsException;
import org.mipams.jumbf.crypto.entities.CryptoException;
import org.mipams.jumbf.crypto.entities.request.CryptoRequest;
import org.mipams.jumbf.crypto.services.CryptoService;
import org.mipams.jumbf.privacy_security.entities.ProtectionDescriptionBox;
import org.mipams.jumbf.privacy_security.services.content_types.ProtectionContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
public class EncryptAssertionService {

    @Autowired
    CryptoService cryptoService;

    @Autowired
    CoreParserService coreParserService;

    @Autowired
    AssertionFactory assertionFactory;

    @Autowired
    JumbfBoxService jumbfBoxService;

    @Value("${org.mipams.fake_media.demo.encScheme}")
    String ENCRYPTION_SCHEME;

    @Value("${org.mipams.fake_media.demo.encSecret}")
    String ENCRYPTION_SECRET;

    public JumbfBox encrypt(RedactableAssertion assertion, String arJumbfBoxLabel,
            ProvenanceMetadata provenanceMetadata) throws MipamsException {

        final String assertionLabel = assertionFactory.getBaseLabel(assertion);
        final int ivSizeInBytes = 16;

        String outputFileUrl = null;
        try {
            byte[] iv = cryptoService.getRandomNumber(ivSizeInBytes);
            String ivAsHex = DatatypeConverter.printHexBinary(iv);

            JumbfBox assertionJumbfBox = assertionFactory.convertAssertionToJumbfBox(assertion, provenanceMetadata);

            outputFileUrl = writeJumbfBoxToAFile(assertionJumbfBox, provenanceMetadata);

            String encryptedContentUrl = encryptFileContent(ivAsHex, outputFileUrl);

            JumbfBox protectionJumbfBox = buildProtectionBox(iv, assertionLabel, arJumbfBoxLabel, encryptedContentUrl);

            return protectionJumbfBox;
        } catch (CryptoException e) {
            throw new MipamsException(e);
        } finally {
            if (outputFileUrl != null) {
                CoreUtils.deleteFile(outputFileUrl);
            }
        }
    }

    private String writeJumbfBoxToAFile(JumbfBox assertionJumbfBox, ProvenanceMetadata provenanceMetadata)
            throws MipamsException {

        String jumbfBoxFileName = CoreUtils.randomStringGenerator();
        String jumbfBoxFilePath = CoreUtils.getFullPath(provenanceMetadata.getParentDirectory(),
                jumbfBoxFileName);

        try (OutputStream fos = new FileOutputStream(jumbfBoxFilePath)) {
            jumbfBoxService.writeToJumbfFile(assertionJumbfBox, fos);
        } catch (IOException e) {
            throw new MipamsException(ProvenanceErrorMessages.JUMBF_BOX_CREATION_ERROR, e);
        }

        return jumbfBoxFilePath;
    }

    private String encryptFileContent(String ivAsHex, String jumbfFilePath) throws MipamsException {

        CryptoRequest encryptionRequest = new CryptoRequest();

        encryptionRequest.setCryptoMethod(ENCRYPTION_SCHEME);
        encryptionRequest.setIv(ivAsHex);
        encryptionRequest.setContentFileUrl(jumbfFilePath);

        try {
            cryptoService.encryptDocument(getSecretKey(), encryptionRequest);
        } catch (CryptoException e) {
            throw new MipamsException(ProvenanceErrorMessages.ENCRYPTION_ERROR, e);
        }

        return null;
    }

    private JumbfBox buildProtectionBox(byte[] iv, String assertionLabel,
            String accessRulesLabel, String encryptedContentFilePath) throws MipamsException {

        JumbfBoxBuilder builder = new JumbfBoxBuilder();

        builder.setJumbfBoxAsRequestable();
        builder.setLabel(assertionLabel);
        builder.setContentType(new ProtectionContentType());

        ProtectionDescriptionBox pdBox = new ProtectionDescriptionBox();
        pdBox.setAes256CbcWithIvProtection();
        pdBox.setIv(iv);
        pdBox.setArLabel(accessRulesLabel);

        BinaryDataBox bdBox = new BinaryDataBox();
        bdBox.setFileUrl(encryptedContentFilePath);

        builder.appendContentBox(pdBox);
        builder.appendContentBox(bdBox);

        return builder.getResult();
    }

    public JumbfBox decrypt(ProtectionDescriptionBox pdBox, BinaryDataBox bdBox) throws MipamsException {
        try {
            if (!pdBox.isAes256CbcWithIvProtection()) {
                throw new MipamsException(ProvenanceErrorMessages.UNSUPPORTED_ENCRYPTION);
            }

            CryptoRequest decryptionRequest = new CryptoRequest();
            decryptionRequest.setContentFileUrl(bdBox.getFileUrl());
            decryptionRequest.setIv(DatatypeConverter.printHexBinary(pdBox.getIv()));
            decryptionRequest.setCryptoMethod(ENCRYPTION_SCHEME);

            String outputFileUrl = cryptoService.decryptDocument(getSecretKey(), decryptionRequest);

            JumbfBox decryptedJumbfBox = coreParserService.parseMetadataFromFile(outputFileUrl).get(0);
            return decryptedJumbfBox;
        } catch (CryptoException e) {
            throw new MipamsException(e);
        }
    }

    private SecretKey getSecretKey() throws MipamsException {
        byte[] aesKeyData = DatatypeConverter.parseHexBinary(ENCRYPTION_SECRET);

        try {
            SecretKeySpec secretKeySpec = new SecretKeySpec(aesKeyData, "AES");
            SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("AES");
            return keyFactory.generateSecret(secretKeySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new MipamsException(e);
        }
    }

    public boolean userHasAccessToResource(UserDetails userDetails, JumbfBox accessRulesJumbfBox)
            throws MipamsException {

        String username = userDetails.getUsername();
        String rolesAsList = authoritiesToList(userDetails.getAuthorities());
        JumbfBox accessRules = accessRulesJumbfBox;

        try {
            return !cryptoService.accessRulesVerifiedSuccessfully(username, rolesAsList, accessRules);
        } catch (CryptoException e) {
            throw new MipamsException(e);
        }
    }

    private String authoritiesToList(Collection<? extends GrantedAuthority> authorities) {
        StringBuilder result = new StringBuilder();

        for (GrantedAuthority auth : authorities) {
            result.append(auth.getAuthority());
            if (result.length() > 0) {
                result.append(",");
            }

        }

        return result.toString();
    }

    public JumbfBox defineAccessRulesForAssertion(String accessRulesLabel, String rule, ProvenanceMetadata metadata)
            throws MipamsException {
        JumbfBoxBuilder builder = new JumbfBoxBuilder();

        builder.setJumbfBoxAsRequestable();
        builder.setLabel(accessRulesLabel);
        builder.setContentType(new JsonContentType());

        JsonBox jsonBox = new JsonBox();

        String jsonRuleUrl = CoreUtils.getFullPath(metadata.getParentDirectory(),
                CoreUtils.randomStringGenerator() + ".json");

        if (rule == null) {
            rule = "{\"rule\": \"user must be connected\"}";
        }

        try (OutputStream os = new FileOutputStream(jsonRuleUrl)) {
            os.write(rule.getBytes());
        } catch (IOException e) {
            throw new MipamsException(e);
        }

        jsonBox.setFileUrl(jsonRuleUrl);

        builder.appendContentBox(jsonBox);

        return builder.getResult();
    }

}