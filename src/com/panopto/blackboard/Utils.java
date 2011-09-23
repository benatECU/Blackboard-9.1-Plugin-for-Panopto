package com.panopto.blackboard;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import blackboard.base.FormattedText;
import blackboard.data.content.Content;
import blackboard.persist.BbPersistenceManager;
import blackboard.persist.Id;
import blackboard.persist.content.ContentDbLoader;
import blackboard.persist.content.ContentDbPersister;
import blackboard.platform.persistence.PersistenceServiceFactory;
import blackboard.platform.plugin.PlugInUtil;
import blackboard.platform.security.SecurityUtil;

// Utility methods applicable outside the context of a particular Blackboard course 
public class Utils {

	// These identify the Building Block to Blackboard.  Set in bb-manifest.xml.
	public static final String vendorID = "ppto";
	public static final String pluginHandle = "PanoptoCourseTool";
	
	// Global settings for the plugin, applicable across all courses.
	public static final Settings pluginSettings = new Settings();

	public static final String courseConfigScriptURL = "Course_Config.jsp";
	public static final String contentScriptURL = "Content.jsp";
	public static final String createScriptURL = "Item_Create.jsp";
	public static final String modifyScriptURL = "Item_Modify.jsp";
	public static final String logScriptURL = "Logs.jsp";
	public static final String buildingBlockManagerURL = "/webapps/blackboard/admin/manage_plugins.jsp";

	public static final String logFilename = "log.txt"; 
	
	public static void log(String logMessage)
	{
		log(null, logMessage);
	}
	
	public static void log(Exception e, String logMessage)
	{
		try
		{
			File configDir = PlugInUtil.getConfigDirectory(vendorID, pluginHandle);
			File logFile = new File(configDir, logFilename);

			FileWriter fileStream = new FileWriter(logFile, true);
			BufferedWriter bufferedStream = new BufferedWriter(fileStream);
			PrintWriter output = new PrintWriter(bufferedStream);
			
			output.write(new Date().toString() + ": " + logMessage);
			
			if(!logMessage.endsWith(System.getProperty("line.separator")))
			{
				output.println();
			}
			
			if(e != null)
			{
				e.printStackTrace(output);
			}
			
			output.println("===================================================================================");
			
			output.close();
		}
		catch(Exception ex)
		{
		}
	}
	
	public static String getLogData()
	{
		StringBuilder contents = new StringBuilder();

		try
		{
			File configDir = PlugInUtil.getConfigDirectory(vendorID, pluginHandle);
			File logFile = new File(configDir, logFilename);
			
			BufferedReader input =  new BufferedReader(new FileReader(logFile));

			try
			{
		        String line = null;
		        
		        while (( line = input.readLine()) != null)
		        {
		          contents.append(line);
		          contents.append(System.getProperty("line.separator"));
		        }
			}
			finally
			{
		        input.close();
			}
		}
		catch(Exception e)
		{
		}

		return contents.toString();
	}
	
	public static void clearLogData()
	{
		try
		{
			File configDir = PlugInUtil.getConfigDirectory(vendorID, pluginHandle);
			File logFile = new File(configDir, logFilename);
			
			logFile.delete();
		}
		catch(Exception e)
		{
		}
	}

	// Check for system tools configuration entitlement
	public static boolean userCanConfigureSystem()
	{
		return SecurityUtil.userHasEntitlement("system.configure-tools.EXECUTE");
	}
	
	// Check for course tools conifguration entitlement
	public static boolean userCanConfigureCourse()
	{
		return SecurityUtil.userHasEntitlement("course.configure-tools.EXECUTE");
	}
	
	// URL to Course Documents page (for custom content importer).
	public static String getCourseDocsURL(String course_id, String content_id)
	{
		return PlugInUtil.getEditableContentReturnURL(content_id, course_id);
	}
	
	public static List<String> getServerList()
	{
		return pluginSettings.getServerList();
	}
	
	// Generate options for drop-down list of servers with optional current selection (serverName)
	public static String generateServerOptionsHTML(String serverName)
	{
		StringBuffer result = new StringBuffer();

		boolean hasSelection = false;
		
    	for(String strServerName : getServerList())
		{
			result.append("<option");
			result.append(" value='" + strServerName + "'");
			if(strServerName.equals(serverName))
			{
				result.append(" SELECTED");
				hasSelection = true;
			}
			result.append(">");
	 		result.append(strServerName);
	 		result.append("</option>\n");
		}

    	if(!hasSelection)
    	{
    		result.insert(0, "<option value=''>-- Select a Server --</option>");
    	}
    	
    	return result.toString();
	}
	
	// Convert a Blackboard username into a Panopto user key referencing this Blackboard instance.
	public static String decorateBlackboardUserName(String bbUserName)
	{
		return pluginSettings.getInstanceName() + "\\" + bbUserName;
	}
	
	// Decorate the course ID with the instance name to generate an external course ID for Panopto Focus.
	public static String decorateBlackboardCourseID(String courseId)
	{
		return (pluginSettings.getInstanceName() + ":" + courseId); 
	}

	// Sign the payload with the proof that it was generated by trusted code. 
	public static String generateAuthCode(String serverName, String payload)
	{
		String signedPayload = payload + "|" + pluginSettings.getApplicationKey(serverName);
		
		String authCode = null;
		try
		{
			MessageDigest m = MessageDigest.getInstance("SHA-1");
			m.update(signedPayload.getBytes(), 0, signedPayload.length());
			
			byte[] digest = m.digest();
			BigInteger digestAsInteger = new BigInteger(1, digest);
			
			// Format as 0-padded hex string.  Length is 2 hex chars per byte.
			String hexPlusLengthFormatString = "%0" + (digest.length * 2) + "X";

			authCode = String.format(hexPlusLengthFormatString, digestAsInteger);
		}
		catch(Exception e)
		{
			Utils.log(e, "Error generating auth code.");
		}
		
		return authCode;
	}
	
	// Verify that the payload was generated by trusted code.
	public static boolean validateAuthCode(String serverName, String payload, String auth)
	{
		String auth_computed = generateAuthCode(serverName, payload);

		return ((auth_computed != null) && (auth_computed.equals(auth)));
	}
	
	// Update content item title and description in DB (link is immutable).
	public static void updatePanoptoContentItem(String title, String description, String content_id)
	{
		try
		{
			Content content = loadPanoptoContentItem(content_id);
			content.setTitle(title);
			FormattedText text = new FormattedText(description, FormattedText.Type.HTML);
			content.setBody(text);
	
			BbPersistenceManager bbPm = PersistenceServiceFactory.getInstance().getDbPersistenceManager();
			ContentDbPersister persister = (ContentDbPersister) bbPm.getPersister( ContentDbPersister.TYPE );
			persister.persist( content );
		}
		catch(Exception e)
		{
			Utils.log(e, String.format("Error updating content item (title: %s, description: %s, content ID: %s).", title, description, content_id));
		}
	}
	
	// Load the specified content item from the DB.
	public static Content loadPanoptoContentItem(String content_id)
	{
		Content content = null;
		
		try
		{
			BbPersistenceManager bbPm = PersistenceServiceFactory.getInstance().getDbPersistenceManager();
			ContentDbLoader loader = (ContentDbLoader) bbPm.getLoader( ContentDbLoader.TYPE );
			Id contentId = bbPm.generateId( Content.DATA_TYPE, content_id );
			content = loader.loadById( contentId );		
		}
		catch (Exception e)
		{
			Utils.log(e, String.format("Error loading content item (content ID: %s).", content_id));
		}

		return content;
	}	

	public static String checkAndEscapeTerminalUrlParam(String url, String terminalUrlParam)
	{
		String urlWithEscapedParam;

		// Greedily match up to the specified parameter.
		// Match parameter value (from '=' to end of string) containing URL chars ':' '/' or '?'.
		// Store parameter value as capture group.
		String regex = ".*[?&]" + terminalUrlParam + "=(.*[:/?].*)$";
		
		Matcher urlParamMatcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(url);
		
		// If we found URL chars, replace the captured parameter value with its URL-encoded value.
		if(urlParamMatcher.matches())
		{
			try
			{
				String quotedCallback = URLEncoder.encode(urlParamMatcher.group(1), "UTF-8");
				urlWithEscapedParam = url.substring(0, urlParamMatcher.start(1)) + quotedCallback;
			}
			catch(UnsupportedEncodingException ex)
			{
				// Oh well, we tried.
				urlWithEscapedParam = url;
			}
		}
		// Just return the original URL if we don't get a match.
		else
		{
			urlWithEscapedParam = url;
		}
		
		return urlWithEscapedParam;
	}
	
	// Found online, Java has no String.join
	public static <T> String join(final Iterable<T> objs, final String delimiter)
	{
	    Iterator<T> iter = objs.iterator();
	    if (!iter.hasNext())
		{
	        return "";
		}
	    StringBuffer buffer = new StringBuffer(String.valueOf(iter.next()));
	    while (iter.hasNext())
		{
	        buffer.append(delimiter).append(String.valueOf(iter.next()));
		}
	    return buffer.toString();
	}
	
	// Found online, Java has no HTML escape function.
	public static String escapeHTML(String text)
	{
		StringBuilder escaped = new StringBuilder();
		StringCharacterIterator iterator = new StringCharacterIterator(text);
		char character =  iterator.current();
		while (character != CharacterIterator.DONE )
		{
			if (character == '<')
			{
				escaped.append("&lt;");
			}
			else if (character == '>')
			{
				escaped.append("&gt;");
			}
			else if (character == '&')
			{
				escaped.append("&amp;");
			}
			else if (character == '\"')
			{
				escaped.append("&quot;");
			}
			else if (character == '\'')
			{
				escaped.append("&#039;");
			}
			else if (character == '(')
			{
				escaped.append("&#040;");
			}
			else if (character == ')')
			{
				escaped.append("&#041;");
			}
			else if (character == '#')
			{
				escaped.append("&#035;");
			}
			else if (character == '%')
			{
				escaped.append("&#037;");
			}
			else if (character == ';')
			{
				escaped.append("&#059;");
			}
			else if (character == '+')
			{
				escaped.append("&#043;");
			}
			else if (character == '-')
			{
				escaped.append("&#045;");
			}
			else
			{
				escaped.append(character);
			}
			
			character = iterator.next();
		}

		return escaped.toString();
	}

	// Uses a simple encoding to serialize an array of strings into a single string
	public static String encodeArrayOfStrings(String[] input)
	{
		if (input == null)
		{
			return null;
		}
		StringBuilder retVal = new StringBuilder();
		for (String val : input)
		{
			retVal.append('"' + val.replaceAll("\"", "\"\"") + "\",");
		}
		
		return retVal.toString();
	}
	
	// Uses a simple encoding to de-serialize an array of strings from a single string
	public static String[] decodeArrayOfStrings(String input)
	{
		if (input == null || input.isEmpty())
		{
			return null;
		}
		// Explicitly handle the legacy case of just a single unquoted string
		else if (!input.startsWith("\"") && !input.endsWith("\","))
		{
			return new String[] { input };
		}
		
		ArrayList<String> retVal = retVal = new ArrayList<String>();
		StringBuilder val = val = new StringBuilder();
		int i = 0;

		// This outer while will loop once per entry in the string
		while (i < input.length())
		{
			// Each entry must start with a "
			if (input.charAt(i) != '"')
			{
				Utils.log(String.format("Missing opening quote at %d in encoded string %s\n", i, input));
				return null;
			}
			i++;
			
			// The inner while will loop over the characters in an entry 
			boolean foundEndQuote = false;
			while (i < input.length() && !foundEndQuote)
			{
				// We found a quote. It might be an end quote
				if (input.charAt(i) == '"')
				{
					if (i+1 < input.length() && input.charAt(i+1) == '"')
					{
						// We found two quotes in a row, so add a single quote to the output and move on
						val.append('"');
						i+=2;
					}
					else
					{
						// We found a quote by itself. This terminates the entry
						i++;
						foundEndQuote = true;
					}
				}
				else
				{
					// We fond some non-quote character. Copy it to the buffer and move on
					val.append(input.charAt(i));
					i++;
				}
			}

			// If we exited the inner loop without finding the closing quote it means we ran out of characters
			if (!foundEndQuote)
			{
				Utils.log(String.format("Missing closing quote at %d in encoded string %s\n", i, input));
				return null;
			}
			
			// Only add non-empty entries
			if (val.length() > 0)
			{
				retVal.add(val.toString());
			}
			
			val = new StringBuilder();
			
			// Each entry (including the last) should end with a comma.
			if (i >= input.length() || input.charAt(i) != ',')
			{
				Utils.log(String.format("Missing closing comma at %d in encoded string %s\n", i, input));
				return null;
			}
			i++;
		}
		
		return retVal.toArray(new String[0]);
	}
}