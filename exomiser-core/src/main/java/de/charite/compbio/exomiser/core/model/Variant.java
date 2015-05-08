/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package de.charite.compbio.exomiser.core.model;

import de.charite.compbio.jannovar.annotation.Annotation;
import de.charite.compbio.jannovar.annotation.VariantEffect;
import java.util.List;

/**
 *
 * @author Jules Jacobsen <jules.jacobsen@sanger.ac.uk>
 */
public interface Variant extends VariantCoordinates {
    
    public double getPhredScore();

    public boolean isXChromosomal();

    public boolean isYChromosomal();

    public boolean isOffExome();

    public VariantEffect getVariantEffect();
    
    public List<Annotation> getAnnotations();

    public String getGeneSymbol();

    public int getEntrezGeneId();

    /**
     * @return a String such as chr6:g.29911092G>T
     */
    public String getChromosomalVariant();

}