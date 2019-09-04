package nl.knaw.huc.di.nde.recipe;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import nl.knaw.huc.di.nde.Registry;
import nl.knaw.huc.di.nde.TermDTO;
import nl.knaw.huc.di.nde.RefDTO;
import nl.mpi.tla.util.Saxon;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Scanner;
import org.json.*;


public class GeoNames implements RecipeInterface {
	
    final static public Map<String,String> NAMESPACES = new LinkedHashMap<>();
    
    static {
        NAMESPACES.putAll(Registry.NAMESPACES);
        NAMESPACES.put("gn", "http://www.geonames.org/ontology#");
        NAMESPACES.put("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
    };
    
    
    private static String streamToString(InputStream inputStream) {
        String text = new Scanner(inputStream, "UTF-8").useDelimiter("\\Z").next();
        return text;
    }

    public static String jsonGetRequest(URL url) {
        String json = null;
        try {
          HttpURLConnection connection = (HttpURLConnection) url.openConnection();
          connection.setDoOutput(true);
          connection.setInstanceFollowRedirects(false);
          connection.setRequestMethod("GET");
          connection.setRequestProperty("Content-Type", "application/json");
          connection.setRequestProperty("charset", "utf-8");
          connection.connect();
          InputStream inStream = connection.getInputStream();
          json = streamToString(inStream); // input stream to string
        } catch (IOException ex) {
          ex.printStackTrace();
        }
        return json;
    }

    
    private static List<TermDTO> processItems(Iterator<XdmItem> iter, List<TermDTO> terms)
    {
    	System.err.println("processing***");
        while (iter.hasNext()) {
        	System.err.println("looping");
        	TermDTO term = null;
        	try
        	{
        		
            XdmItem item = iter.next();
            term = new TermDTO();
            term.uri = new URI(Saxon.xpath2string(item, "@rdf:about", null, GeoNames.NAMESPACES));
            // properties
            for (Iterator<XdmItem> lblIter = Saxon.xpathIterator(item, "gn:name",null, GeoNames.NAMESPACES); lblIter.hasNext();) {
                term.prefLabel.add(lblIter.next().getStringValue());
            }

            for (Iterator<XdmItem> lblIter = Saxon.xpathIterator(item, "gn:alternateName",null, GeoNames.NAMESPACES); lblIter.hasNext();) {
                XdmItem altNameItem = lblIter.next();
            	String lang = Saxon.xpath2string(altNameItem, "@xml:lang", null, GeoNames.NAMESPACES);
            	//only show alt names in Dutch or English
            	if(lang.equalsIgnoreCase("nl")|| lang.equalsIgnoreCase("en"))
            	{
                	term.altLabel.add(altNameItem.getStringValue());
            	}
            }            
 
            //also need to pick up official names, as official names in other languages are not as alternateName but official name
            for (Iterator<XdmItem> lblIter = Saxon.xpathIterator(item, "gn:officialName",null, GeoNames.NAMESPACES); lblIter.hasNext();) {
                XdmItem altNameItem = lblIter.next();
            	String lang = Saxon.xpath2string(altNameItem, "@xml:lang", null, GeoNames.NAMESPACES);

            	//only show official names in Dutch or English
            	if(lang.equalsIgnoreCase("nl")|| lang.equalsIgnoreCase("en"))
            	{
                	term.altLabel.add(altNameItem.getStringValue());
            	}
            } 
            
            for (Iterator<XdmItem> lblIter = Saxon.xpathIterator(item, "gn:parentCountry",null, GeoNames.NAMESPACES); lblIter.hasNext();) {
                XdmItem countryItem = lblIter.next();
            	URI countryUri = new URI(Saxon.xpath2string(countryItem, "@rdf:resource", null, GeoNames.NAMESPACES));
                XdmNode countryRes = Saxon.buildDocument(new StreamSource(countryUri.toString() + "about.rdf"));
                Iterator<XdmItem> countryNameIter = Saxon.xpathIterator(countryRes, "/rdf:RDF/gn:Feature/gn:name",null, GeoNames.NAMESPACES);
                while(countryNameIter.hasNext())
                {
                	RefDTO parentCountry = new RefDTO();
                	parentCountry.url = countryUri.toString();
                	parentCountry.label = countryNameIter.next().getStringValue();
                	term.related.add(parentCountry);
                }
            }
            
            //misusing the definition property here for links to the wikipedia and dbpedia articles
            //need to consider a better alternative if we decide this information is worth displaying
            //possibly subclassing TermDTO??
            for (Iterator<XdmItem> lblIter = Saxon.xpathIterator(item, "gn:wikipediaArticle",null, GeoNames.NAMESPACES); lblIter.hasNext();) {
                XdmItem articleItem = lblIter.next();
            	term.definition.add(Saxon.xpath2string(articleItem, "@rdf:resource", null, GeoNames.NAMESPACES));
            }
            for (Iterator<XdmItem> lblIter = Saxon.xpathIterator(item, "rdfs:seeAlso",null, GeoNames.NAMESPACES); lblIter.hasNext();) {
                XdmItem articleItem = lblIter.next();
            	term.definition.add(Saxon.xpath2string(articleItem, "@rdf:resource", null, GeoNames.NAMESPACES));
            }	

            terms.add(term);
        	}
        	catch (SaxonApiException | URISyntaxException ex) {
                Logger.getLogger(GeoNames.class.getName()).log(Level.SEVERE, null, ex);
            }
    }
        System.err.println("processed");
        return terms;
    }

    @Override
    public List<TermDTO> fetchMatchingTerms(XdmItem config, String match) {
        List<TermDTO> terms = new ArrayList<>();
        try {
            System.err.println("DBG: Lets cook some GeoNames!");
            String api = Saxon.xpath2string(config, "nde:api", null, Registry.NAMESPACES);           
            // see if api supports the use of '*'; should be boolean instead of string
            String wildcard = Saxon.xpath2string(config, "nde:wildcard",null, Registry.NAMESPACES);
            String user = Saxon.xpath2string(config, "nde:user",null, Registry.NAMESPACES);

            // remove '*' if wildcards are not supported
            if ( wildcard.equals("no") ) {
                match = match.replaceAll("\\*","");
            }

            // encode the match string 
            match = URLEncoder.encode(match, "UTF-8");

            System.err.println("DBG: Ingredients:");
            System.err.println("DBG: - instance["+Saxon.xpath2string(config, "(nde:label)[1]", null, Registry.NAMESPACES)+"]");
            System.err.println("DBG: - api["+api+"]");
            System.err.println("DBG: - match["+match+"]");

            //NOTE: there is a version of the API that returns JSON, but that doesn't then return the RDF identifier
            //we specify:
            // - results in RDF
            // - return Dutch label for the main name
            // - search only for populated places and independent political entities http://www.geonames.org/export/codes.html
            // - search in Dutch labels (mainly to avoid searching in pseudo-languages such as airline codes)
            
            //first for the specified feature classes
            //populated places and country/state/region
            URL url = new URL(api + "search?name="+  match + "&username=" + user + "&type=rdf&lang=nl&featureClass=P&featureClass=A&searchlang=nl");
            System.err.println("DBG: = url["+url+"]");
            XdmNode res = Saxon.buildDocument(new StreamSource(url.toString()));
            Iterator<XdmItem> iter = Saxon.xpathIterator(res, "/rdf:RDF/gn:Feature",null, GeoNames.NAMESPACES);
           
            processItems(iter, terms);          
 
            //then for specified feature codes (can't do both at the same time)
            //roads, airports, theatres (for now as demo)
            url = new URL(api + "search?name="+  match + "&username=" + user + "&type=rdf&lang=nl&featureCode=RD&featureCode=AIRP&featureCode=THTR&searchlang=nl");
            System.err.println("DBG: = url["+url+"]");
            res = Saxon.buildDocument(new StreamSource(url.toString()));
            iter = Saxon.xpathIterator(res, "/rdf:RDF/gn:Feature",null, GeoNames.NAMESPACES);
           
            processItems(iter, terms); 
            
            if(terms.isEmpty()) {
            	// try again without the search language specified - not the preference as this also returns matches on airport codes
            	System.err.println("No results, trying again without search language");
                url = new URL(api + "search?name="+  match + "&username=" + user + "&type=rdf&lang=nl&featureClass=P&featureClass=PCLI");
                System.err.println("DBG: = url["+url+"]");
                res = Saxon.buildDocument(new StreamSource(url.toString()));
                iter = Saxon.xpathIterator(res, "/rdf:RDF/gn:Feature",null, GeoNames.NAMESPACES);
                processItems(iter, terms);
                
                url = new URL(api + "search?name="+  match + "&username=" + user + "&type=rdf&lang=nl&featureCode=RD&featureCode=AIRP&featureCode=THTR&searchlang=nl");
                System.err.println("DBG: = url["+url+"]");
                res = Saxon.buildDocument(new StreamSource(url.toString()));
                iter = Saxon.xpathIterator(res, "/rdf:RDF/gn:Feature",null, GeoNames.NAMESPACES);
               
                processItems(iter, terms); 
            }

             
        } catch (IOException | SaxonApiException ex) {
            Logger.getLogger(GeoNames.class.getName()).log(Level.SEVERE, null, ex);
        }
        return terms;
    }
    
}
