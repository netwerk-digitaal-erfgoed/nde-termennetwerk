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

//Potentially interesting web services

//http://www.geonames.org/export/place-hierarchy.html#children
//http://www.geonames.org/export/place-hierarchy.html#contains
//http://www.geonames.org/export/place-hierarchy.html#hierarchy
//http://www.geonames.org/export/geonames-search.html

public class GeoNames implements RecipeInterface {
	
    final static public Map<String,String> NAMESPACES = new LinkedHashMap<>();
    
    static {
        NAMESPACES.putAll(Registry.NAMESPACES);
        NAMESPACES.put("gn", "http://www.geonames.org/ontology#");
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

    // function translating the results into GraphQL Term vars
    private static TermDTO processItem(JSONObject itemObject, TermDTO term, String base) {

        String name =""; String uristr = ""; String id = ""; 
        try {
            if (itemObject.has("name")) { name = itemObject.getString("name"); }
            if (itemObject.has("uri")) { uristr = itemObject.getString("uri"); }
            if (itemObject.has("id")) { uristr = itemObject.getString("id"); }
            if ( !uristr.toLowerCase().contains("http") ) {
                // build a uri based on the erfgeo url in 'base'
                uristr = base + uristr; 
            }
            // first start with uri and prefLabel
            if (term.uri == null ) {
                term.uri= new URI(uristr);
                term.prefLabel.add(name);
            }
            // all the others are added as related terms
            else {
                RefDTO ref = new RefDTO();
                ref.label=name;
                ref.url=uristr;
                term.related.add(ref);
            }
        }
        catch (URISyntaxException ex) {
            Logger.getLogger(GeoNames.class.getName()).log(Level.SEVERE, null, ex);
        }
        return term;
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

            URL url = new URL(api + "search?name="+  match + "&username=" + user + "&type=rdf");
            System.err.println("DBG: = url["+url+"]");

            TermDTO term = null;
            XdmNode res = Saxon.buildDocument(new StreamSource(url.toString()));
            System.err.println(res);
            for (Iterator<XdmItem> iter = Saxon.xpathIterator(res, "/rdf:RDF/gn:Feature",null, GeoNames.NAMESPACES); iter.hasNext();) {
            	System.err.println("here");
                XdmItem item = iter.next();
                System.err.println(item);
                term = new TermDTO();
                term.uri = new URI(Saxon.xpath2string(item, "@rdf:about", null, GeoNames.NAMESPACES));
                // properties
                for (Iterator<XdmItem> lblIter = Saxon.xpathIterator(item, "gn:name",null, GeoNames.NAMESPACES); lblIter.hasNext();) {
                    term.prefLabel.add(lblIter.next().getStringValue());
                }
                for (Iterator<XdmItem> lblIter = Saxon.xpathIterator(item, "gn:alternateName",null, GeoNames.NAMESPACES); lblIter.hasNext();) {
                    term.altLabel.add(lblIter.next().getStringValue());
                }
                
                terms.add(term);
            }
             
        } catch (IOException | SaxonApiException | URISyntaxException ex) {
            Logger.getLogger(GeoNames.class.getName()).log(Level.SEVERE, null, ex);
        }
        return terms;
    }
    
}
