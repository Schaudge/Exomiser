/*
 * The Exomiser - A tool to annotate and prioritize genomic variants
 *
 * Copyright (c) 2016-2020 Queen Mary University of London.
 * Copyright (c) 2012-2016 Charité Universitätsmedizin Berlin and Genome Research Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.monarchinitiative.exomiser.core.genome;

import de.charite.compbio.jannovar.annotation.SVAnnotations;
import de.charite.compbio.jannovar.annotation.SVAnnotator;
import de.charite.compbio.jannovar.annotation.VariantAnnotations;
import de.charite.compbio.jannovar.annotation.VariantAnnotator;
import de.charite.compbio.jannovar.annotation.builders.AnnotationBuilderOptions;
import de.charite.compbio.jannovar.data.JannovarData;
import de.charite.compbio.jannovar.data.ReferenceDictionary;
import de.charite.compbio.jannovar.reference.*;
import org.monarchinitiative.exomiser.core.model.AllelePosition;
import org.monarchinitiative.exomiser.core.model.ConfidenceInterval;
import org.monarchinitiative.exomiser.core.model.VariantType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wrapper to build Jannovar annotations for variants. CAUTION! This class returns native Jannovar objects which use zero-based
 * coordinates.
 *
 * @author Jules Jacobsen <j.jacobsen@qmul.ac.uk>
 */
public class JannovarAnnotationService {

    private static final Logger logger = LoggerFactory.getLogger(JannovarAnnotationService.class);

    // Regular expression pattern for matching breakends in VCF.
    private static final Pattern BND_PATTERN = Pattern.compile(
            "^(?<leadingBases>\\w*)(?<firstBracket>[\\[\\]])(?<targetChrom>[^:]+):(?<targetPos>\\w+)(?<secondBracket>[\\[\\]])(?<trailingBases>\\w*)$");

    //in cases where a variant cannot be positioned on a chromosome we're going to use 0 in order to fulfil the
    //requirement of a variant having an integer chromosome
    private static final int UNKNOWN_CHROMOSOME = 0;

    private final ReferenceDictionary referenceDictionary;
    private final VariantAnnotator variantAnnotator;
    private final SVAnnotator structuralVariantAnnotator;

    public JannovarAnnotationService(JannovarData jannovarData) {
        this.referenceDictionary = jannovarData.getRefDict();
        this.variantAnnotator = new VariantAnnotator(jannovarData.getRefDict(), jannovarData.getChromosomes(), new AnnotationBuilderOptions());
        this.structuralVariantAnnotator = new SVAnnotator(jannovarData.getRefDict(), jannovarData.getChromosomes());
    }

    /**
     * Takes VCF (forward-strand, one-based) style variants and returns a set of Jannovar {@link VariantAnnotations}.
     *
     * @param contig
     * @param pos
     * @param ref
     * @param alt
     * @return a set of {@link VariantAnnotations} for the given variant coordinates. CAUTION! THE RETURNED ANNOTATIONS
     * WILL USE ZERO-BASED COORDINATES AND WILL BE TRIMMED LEFT SIDE FIRST, ie. RIGHT SHIFTED. This is counter to VCF
     * conventions.
     */
    public VariantAnnotations annotateVariant(String contig, int pos, String ref, String alt) {
        GenomePosition genomePosition = buildGenomePosition(contig, pos);
        GenomeVariant genomeVariant = new GenomeVariant(genomePosition, ref, alt);
        return annotateGenomeVariant(genomeVariant);
    }

    private GenomePosition buildGenomePosition(String contig, int pos) {
        int chr = getIntValueOfChromosomeOrZero(contig);
        return new GenomePosition(referenceDictionary, Strand.FWD, chr, pos, PositionType.ONE_BASED);
    }

    private int getIntValueOfChromosomeOrZero(String contig) {
        return referenceDictionary.getContigNameToID().getOrDefault(contig, UNKNOWN_CHROMOSOME);
    }

    private VariantAnnotations annotateGenomeVariant(GenomeVariant genomeVariant) {
        if (genomeVariant.getChr() == UNKNOWN_CHROMOSOME) {
            //Need to check this here and return otherwise the variantAnnotator will throw a NPE.
            return VariantAnnotations.buildEmptyList(genomeVariant);
        }
        try {
            return variantAnnotator.buildAnnotations(genomeVariant);
        } catch (Exception e) {
            logger.debug("Unable to annotate variant {}-{}-{}-{}",
                    genomeVariant.getChrName(),
                    genomeVariant.getPos(),
                    genomeVariant.getRef(),
                    genomeVariant.getAlt(),
                    e);
        }
        return VariantAnnotations.buildEmptyList(genomeVariant);
    }

    /**
     * @param variantType of the variant
     * @param alt         alternate allele representation. This should be a symbolic type such as '<INS>'
     * @param startContig Starting contig the variant is located on. Corresponds to the VCF 'CHROM' field.
     * @param startPos    Starting position on the reference sequence variant is located at. Corresponds to the VCF 'POS' field.
     * @param startCi     Confidence interval surrounding the startPos field. Corresponds to VCF 'CIPOS' field.
     * @param endContig
     * @param endPos      End position on the reference sequence variant is located at. Corresponds to the VCF 'END' field.
     * @param endCi       Confidence interval surrounding the endPos field. Corresponds to VCF 'CIEND' field.
     * @return a set of {@link VariantAnnotations} for the given variant coordinates. CAUTION! THE RETURNED ANNOTATIONS
     * WILL USE ZERO-BASED COORDINATES AND WILL BE TRIMMED LEFT SIDE FIRST, ie. RIGHT SHIFTED. This is counter to VCF
     * conventions.
     * @since 13.0.0
     */
    public SVAnnotations annotateStructuralVariant(VariantType variantType, String alt, String startContig, int startPos, ConfidenceInterval startCi, String endContig, int endPos, ConfidenceInterval endCi) {
        GenomePosition start = buildGenomePosition(startContig, startPos);
        GenomePosition end = buildGenomePosition(endContig, endPos);

        SVGenomeVariant svGenomeVariant = buildSvGenomeVariant(variantType, alt, start, startCi, end, endCi);
        // Unsupported types
        if (!AllelePosition.isSymbolic(alt)) {
            logger.warn("{}-{}-?-{} {} is not a symbolic allele - returning empty annotations", startContig, startPos, alt, variantType);
            return SVAnnotations.buildEmptyList(svGenomeVariant);
        }

        try {
            return structuralVariantAnnotator.buildAnnotations(svGenomeVariant);
        } catch (Exception e) {
            logger.debug("Unable to annotate variant {}-{}-{}",
                    svGenomeVariant.getChrName(),
                    svGenomeVariant.getPos(),
                    svGenomeVariant.getPos2(),
                    e);
        }
        return SVAnnotations.buildEmptyList(svGenomeVariant);
    }

    private SVGenomeVariant buildSvGenomeVariant(VariantType variantType, String alt, GenomePosition start, ConfidenceInterval startCi, GenomePosition end, ConfidenceInterval endCi) {

        int startCiLower = startCi.getLowerBound();
        int startCiUpper = startCi.getUpperBound();

        int endCiLower = endCi.getLowerBound();
        int endCiUpper = endCi.getUpperBound();

        VariantType svSubType = variantType.getSubType();
        switch (svSubType) {
            case DEL:
                return new SVDeletion(start, end, startCiLower, startCiUpper, endCiLower, endCiUpper);
            case DEL_ME:
                return new SVMobileElementDeletion(start, end, startCiLower, startCiUpper, endCiLower, endCiUpper);
            case DUP:
                return new SVDuplication(start, end, startCiLower, startCiUpper, endCiLower, endCiUpper);
            case DUP_TANDEM:
                return new SVTandemDuplication(start, end, startCiLower, startCiUpper, endCiLower, endCiUpper);
            case INS:
                return new SVInsertion(start, startCiLower, startCiUpper);
            case INS_ME:
                return new SVMobileElementInsertion(start, startCiLower, startCiUpper);
            case INV:
                return new SVInversion(start, end, startCiLower, startCiUpper, endCiLower, endCiUpper);
            case CNV:
                return new SVCopyNumberVariant(start, end, startCiLower, startCiUpper, endCiLower, endCiUpper);
            case BND:
                return buildBreakendVariant(alt, start, end, startCiLower, startCiUpper, endCiLower, endCiUpper);
            default:
                return new SVUnknown(start, end, startCiLower, startCiUpper, endCiLower, endCiUpper);
        }
    }

    private SVGenomeVariant buildBreakendVariant(String alt, GenomePosition start, GenomePosition end, int lowerCiStart, int upperCiStart, int lowerCiEnd, int upperCiEnd) {
        Matcher matcher = BND_PATTERN.matcher(alt);
        if (matcher.matches()) {
            String firstBracket = matcher.group("firstBracket");
            String secondBracket = matcher.group("secondBracket");
            if (firstBracket.equals(secondBracket)) {
                String contig2 = matcher.group("targetChrom");
                int pos2 = Integer.parseInt(matcher.group("targetPos"));
                GenomePosition gBNDPos2 = buildGenomePosition(contig2, pos2);

                String leadingBases = matcher.group("leadingBases");
                String trailingBases = matcher.group("trailingBases");

                return new SVBreakend(
                        start, gBNDPos2, lowerCiStart, upperCiStart, lowerCiEnd, upperCiEnd,
                        leadingBases, trailingBases,
                        "]".equals(firstBracket) ? SVBreakend.Side.LEFT_END : SVBreakend.Side.RIGHT_END);
            }
        }
        logger.error("Invalid BND alternative allele: {}", alt);
        return new SVUnknown(start, end, lowerCiStart, upperCiStart, lowerCiEnd, upperCiEnd);
    }
}
