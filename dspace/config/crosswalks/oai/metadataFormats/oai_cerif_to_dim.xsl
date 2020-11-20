<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0"
	xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
	xmlns:fo="http://www.w3.org/1999/XSL/Format"
	xmlns:dim="http://www.dspace.org/xmlns/dspace/dim"
	xmlns:cerif="https://www.openaire.eu/cerif-profile/1.1/"
	xmlns:pt="https://www.openaire.eu/cerif-profile/vocab/COAR_Publication_Types">
	
	<xsl:param name="nestedMetadataPlaceholder" />
	<xsl:param name="idPrefix" />
	
	<xsl:template match="cerif:Publication">
		<dim:dim>
		
			<dim:field mdschema="dc" element="type" >
				<xsl:value-of select="pt:Type" />
			</dim:field>
		
			<dim:field mdschema="dc" element="language" qualifier="iso">
				<xsl:value-of select="cerif:Language" />
			</dim:field>
		
			<dim:field mdschema="dc" element="title" >
				<xsl:value-of select="cerif:Title" />
			</dim:field>
			
			<xsl:for-each select="cerif:Subtitle">
				<dim:field mdschema="dc" element="title" qualifier="alternative" >
					<xsl:value-of select="current()" />
				</dim:field>
			</xsl:for-each>
			
			<dim:field mdschema="dc" element="relation" qualifier="publication" >
				<xsl:value-of select="cerif:PublishedIn/cerif:Publication/cerif:Title" />
			</dim:field>
			
			<dim:field mdschema="dc" element="relation" qualifier="issn" >
				<xsl:value-of select="cerif:PublishedIn/cerif:Publication/cerif:ISSN" />
			</dim:field>
			
			<dim:field mdschema="dc" element="relation" qualifier="isbn" >
				<xsl:value-of select="cerif:PublishedIn/cerif:Publication/cerif:ISBN" />
			</dim:field>
			
			<dim:field mdschema="dc" element="relation" qualifier="doi" >
				<xsl:value-of select="cerif:PublishedIn/cerif:Publication/cerif:DOI" />
			</dim:field>
			
			<dim:field mdschema="dc" element="publisher" >
				<xsl:value-of select="cerif:Publishers/cerif:Publisher/cerif:DisplayName" />
			</dim:field>
			
			<dim:field mdschema="dc" element="date" qualifier="issued" >
				<xsl:value-of select="cerif:PublicationDate" />
			</dim:field>
			
			<dim:field mdschema="oaire" element="citation" qualifier="volume" >
				<xsl:value-of select="cerif:Volume" />
			</dim:field>
			
			<dim:field mdschema="oaire" element="citation" qualifier="issue" >
				<xsl:value-of select="cerif:Issue" />
			</dim:field>
			
			<dim:field mdschema="oaire" element="citation" qualifier="startPage" >
				<xsl:value-of select="cerif:StartPage" />
			</dim:field>
			
			<dim:field mdschema="oaire" element="citation" qualifier="endPage" >
				<xsl:value-of select="cerif:EndPage" />
			</dim:field>
			
			<xsl:for-each select="cerif:DOI">
				<dim:field mdschema="dc" element="identifier" qualifier="doi" >
					<xsl:value-of select="current()" />
				</dim:field>
			</xsl:for-each>
			
			<xsl:for-each select="cerif:ISBN">
				<dim:field mdschema="dc" element="identifier" qualifier="isbn" >
					<xsl:value-of select="current()" />
				</dim:field>
			</xsl:for-each>
			
			<xsl:for-each select="cerif:ISSN">
				<dim:field mdschema="dc" element="identifier" qualifier="issn" >
					<xsl:value-of select="current()" />
				</dim:field>
			</xsl:for-each>
			
			<xsl:for-each select="cerif:ISI-Number">
				<dim:field mdschema="dc" element="identifier" qualifier="isi" >
					<xsl:value-of select="current()" />
				</dim:field>
			</xsl:for-each>
			
			<xsl:for-each select="cerif:SCP-Number">
				<dim:field mdschema="dc" element="identifier" qualifier="scopus" >
					<xsl:value-of select="current()" />
				</dim:field>
			</xsl:for-each>
			
			<xsl:for-each select="cerif:Keyword">
				<dim:field mdschema="dc" element="subject" >
					<xsl:value-of select="current()" />
				</dim:field>
			</xsl:for-each>
			
			<xsl:for-each select="cerif:Authors/cerif:Author">
				<dim:field mdschema="dc" element="contributor" qualifier="author" >
					<xsl:if test="cerif:Person/@id">
						<xsl:variable name="authorityToSet" select="concat($idPrefix,cerif:Person/@id)" />
						<xsl:attribute name="authority"><xsl:value-of select="$authorityToSet"/></xsl:attribute>
					</xsl:if>
					<xsl:call-template name="nestedMetadataValue">
				    	<xsl:with-param name="value" select="cerif:DisplayName" />
			    	</xsl:call-template>
				</dim:field>
				<dim:field mdschema="oairecerif" element="author" qualifier="affiliation" >
					<xsl:call-template name="nestedMetadataValue">
				    	<xsl:with-param name="value" select="cerif:Affiliation/cerif:OrgUnit/cerif:Name" />
			    	</xsl:call-template>
				</dim:field>
			</xsl:for-each>
			
			<xsl:for-each select="cerif:Editors/cerif:Editor">
				<dim:field mdschema="dc" element="contributor" qualifier="editor" >
					<xsl:if test="cerif:Person/@id">
						<xsl:variable name="authorityToSet" select="concat($idPrefix,cerif:Person/@id)" />
						<xsl:attribute name="authority"><xsl:value-of select="$authorityToSet"/></xsl:attribute>
					</xsl:if>
					<xsl:call-template name="nestedMetadataValue">
				    	<xsl:with-param name="value" select="cerif:DisplayName" />
			    	</xsl:call-template>
				</dim:field>
				<dim:field mdschema="oairecerif" element="editor" qualifier="affiliation" >
					<xsl:call-template name="nestedMetadataValue">
				    	<xsl:with-param name="value" select="cerif:Affiliation/cerif:OrgUnit/cerif:Name" />
			    	</xsl:call-template>
				</dim:field>
			</xsl:for-each>
			
			<dim:field mdschema="dc" element="description" qualifier="abstract" >
				<xsl:value-of select="cerif:Abstract" />
			</dim:field>
			
			<xsl:for-each select="cerif:OriginatesFrom">
				<xsl:if test="cerif:Project">
					<dim:field mdschema="dc" element="relation" qualifier="project" >
						<xsl:if test="cerif:Project/@id">
							<xsl:variable name="authorityToSet" select="concat($idPrefix,cerif:Project/@id)" />
							<xsl:attribute name="authority"><xsl:value-of select="$authorityToSet"/></xsl:attribute>
							<xsl:value-of select="cerif:Project/cerif:Title" />
						</xsl:if>
					</dim:field>
				</xsl:if>
				<xsl:if test="cerif:Funding">
					<dim:field mdschema="dc" element="relation" qualifier="funding" >
						<xsl:if test="cerif:Funding/@id">
							<xsl:variable name="authorityToSet" select="concat($idPrefix,cerif:Funding/@id)" />
							<xsl:attribute name="authority"><xsl:value-of select="$authorityToSet"/></xsl:attribute>
							<xsl:value-of select="cerif:Funding/cerif:Name" />
						</xsl:if>
					</dim:field>
				</xsl:if>
			</xsl:for-each>
			
			<dim:field mdschema="dc" element="relation" qualifier="conference" >
				<xsl:value-of select="cerif:PresentedAt/cerif:Event/cerif:Name" />
			</dim:field>
			
			<dim:field mdschema="dc" element="relation" qualifier="dataset" >
				<xsl:value-of select="cerif:References/cerif:Product/cerif:Name" />
			</dim:field>
			
		</dim:dim>
	</xsl:template>
	
	<xsl:template match="cerif:Project">
		<dim:dim>
		
			<dim:field mdschema="dc" element="title" >
				<xsl:value-of select="cerif:Title" />
			</dim:field>
			
			<dim:field mdschema="oairecerif" element="acronym">
				<xsl:value-of select="cerif:Acronym" />
			</dim:field>
			
			<dim:field mdschema="crispj" element="openaireid" >
				<xsl:value-of select="cerif:Identifier[@type = 'http://namespace.openaire.eu/oaf']" />
			</dim:field>
			
			<dim:field mdschema="oairecerif" element="identifier" qualifier="url" >
				<xsl:value-of select="cerif:Identifier[@type = 'URL']" />
			</dim:field>
			
			<dim:field mdschema="oairecerif" element="project" qualifier="startDate" >
				<xsl:value-of select="cerif:StartDate" />
			</dim:field>
			
			<dim:field mdschema="oairecerif" element="project" qualifier="endDate" >
				<xsl:value-of select="cerif:EndDate" />
			</dim:field>
			
			<dim:field mdschema="oairecerif" element="project" qualifier="status" >
				<xsl:value-of select="cerif:Status" />
			</dim:field>

		</dim:dim>
	</xsl:template>
	
	<xsl:template name="nestedMetadataValue">
		<xsl:param name = "value" />
		<xsl:choose>
			<xsl:when test="$value">
				<xsl:value-of select="$value" />
			</xsl:when>
			<xsl:otherwise>
				<xsl:value-of select="$nestedMetadataPlaceholder"/> 
			</xsl:otherwise>
		</xsl:choose>
	</xsl:template>
	
</xsl:stylesheet>