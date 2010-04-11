package fm.libre.droid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.CoreProtocolPNames;

import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnBufferingUpdateListener;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

public class LibreService extends Service implements OnBufferingUpdateListener, OnCompletionListener {
	private LibreServiceBinder serviceBinder = new LibreServiceBinder();
	private Playlist playlist;
	private String sessionKey;
	private String scrobbleKey;
	private String stationName;
	private int currentSong;
	private int currentPage = 0;
	private boolean loggedIn = false;
	private boolean playing = false;
	private boolean buffering = false;
	private MediaPlayer mp;
	
	@Override
	public void onCreate() {
		super.onCreate();
		this.mp = new MediaPlayer();
		this.currentSong = 0;
        this.playlist = new Playlist();
        this.sessionKey = "";
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		return serviceBinder;
	}

	@Override
	public void onDestroy() {
		this.mp.release();
	}
	
	public String httpGet(String url) throws URISyntaxException, ClientProtocolException, IOException {
    	DefaultHttpClient client = new DefaultHttpClient();
    	URI uri = new URI(url);
		HttpGet method = new HttpGet(uri);
		HttpResponse res = client.execute(method);
		ByteArrayOutputStream outstream = new ByteArrayOutputStream();  
		res.getEntity().writeTo(outstream);
		return outstream.toString();
    }
	
	public String httpPost(String url, String... params) throws URISyntaxException, ClientProtocolException, IOException {
    	DefaultHttpClient client = new DefaultHttpClient();
    	URI uri = new URI(url);
    	HttpPost method = new HttpPost(uri);
    	List<NameValuePair> paramPairs = new ArrayList<NameValuePair>(2);
    	for (int i = 0; i < params.length; i+=2) {
    		paramPairs.add(new BasicNameValuePair(params[i], params[i+1]));  
    	}  
    	method.setEntity(new UrlEncodedFormEntity(paramPairs));
    	method.getParams().setBooleanParameter(CoreProtocolPNames.USE_EXPECT_CONTINUE, false); // Disable expect-continue, caching server doesn't like this
    	HttpResponse res = client.execute(method);
    	ByteArrayOutputStream outstream = new ByteArrayOutputStream();
    	res.getEntity().writeTo(outstream);
    	return outstream.toString();
    }
	
	public boolean login(String username, String password) {
		loggedIn = false;
    	String passMD5 = "";
    	String token = "";
    	long timestamp = new Date().getTime() / 1000;
    	try {
    		MessageDigest md = MessageDigest.getInstance("MD5");
    		md.update(password.getBytes(), 0, password.length());
    		passMD5 = new BigInteger(1, md.digest()).toString(16);
    		if (passMD5.length() == 31) {
    			passMD5 = "0" + passMD5; 
    		}
    		token = passMD5 + Long.toString(timestamp);
    		md.update(token.getBytes(), 0, token.length());
    		token = new BigInteger(1, md.digest()).toString(16);
    		if (token.length() == 31) {
    			token = "0" + token;
    		}
    	} catch (NoSuchAlgorithmException ex) {
    		Toast.makeText(this, "MD5 hashing unavailable, unable to login.", Toast.LENGTH_LONG);
    	}
    	
    	try {
    		// Login for streaming
    		String output = this.httpGet("http://alpha.libre.fm/radio/handshake.php?username=" + username + "&passwordmd5=" + passMD5);
    		if (output.trim().equals("BADAUTH")) {
    			Toast.makeText(this, "Incorrect username or password", Toast.LENGTH_SHORT).show();
    		} else {
    		    String[] result = output.split("[=\n]");
    		    for (int x=0; x<result.length; x++)  {
    		    	if (result[x].trim().equals("session")) {
    		    		this.sessionKey = result[x+1].trim();
    		    	}
    		    }
    		    loggedIn = true;
    		}
    		// Login for scrobbling
    		output = this.httpGet("http://turtle.libre.fm/?hs=true&p=1.2&u=" + username + "&t=" + Long.toString(timestamp) + "&a=" + token + "&c=ldr" );    		
    		if (output.split("\n")[0].equals("OK")) {
    			this.scrobbleKey = output.split("\n")[1].trim();
    		}    		
    	} catch (Exception ex) {
    		Toast.makeText(this, "Unable to connect to libre.fm server: " + ex.getMessage(), Toast.LENGTH_LONG).show();
    	}
    	return loggedIn;
	}
	
	public boolean isLoggedIn() {
		return loggedIn;
	}
	
	public void onBufferingUpdate(MediaPlayer mp, int percent) {
		if (percent > 2 && !mp.isPlaying() && this.playing) {
			this.mp.start();
		}
		if (percent > 99) {
			this.buffering = false;
		}
	}
	
	public void onCompletion(MediaPlayer mp) {
		if(!this.buffering) { // We get spurious complete messages if we're still buffering
			// Scrobble
			Song song = this.playlist.getSong(this.currentSong);
			try { 
				String time = Long.toString(new Date().getTime() / 1000);
				this.httpPost("http://turtle.libre.fm/submissions/1.2/", "s", this.scrobbleKey, "a[0]", song.artist, "t[0]", song.title, "b[0]", song.album, "i[0]", time);
			} catch (Exception ex) {
				Log.d("libredroid", "Couldn't scrobble: " + ex.getMessage());
			}
			
			this.next();			
		}
	}
	
	public void play() {
    	if (this.currentSong >= this.playlist.size()) {
    		this.getPlaylist();
    	}
    	this.playing = true;
    	this.buffering = true;
    	Song song = this.playlist.getSong(currentSong);
    	Log.d("libredroid", "Song: " + this.playlist);
    	try {
    		this.mp.reset();
    		// Hack to get Jamendo MP3 stream instead of OGG because MediaPlayer
    		// doesn't support streaming OGG at the moment
    		this.mp.setDataSource(song.location.replace("ogg2", "mp31"));
    		this.mp.setOnBufferingUpdateListener(this);
            this.mp.setOnCompletionListener(this);
    		this.mp.prepareAsync();
    		// Send now playing data
    		this.httpPost("http://turtle.libre.fm/nowplaying/1.2/", "s", this.scrobbleKey, "a", song.artist, "t", song.title);
    	} catch (Exception ex) {
    		Log.d("libredroid", "Couldn't play " + song.title + ": " + ex.getMessage());
    		this.next();
    	}
    	this.sendBroadcast(new Intent("LibreDroidNewSong"));
    }
	
	public void next() {
    	mp.stop();
    	this.currentSong++;
    	this.play();
    }
    
    public void prev() {
    	if (this.currentSong > 0) {
    		mp.stop();
    		this.currentSong--;
    		this.play();
    	}
    }
    
    public void stop() {
    	mp.stop();
    }
    
    public Song getSong() {
    	return this.getSong(this.currentSong);
    }
    
    public Song getSong(int songNumber) {
    	return this.playlist.getSong(songNumber);
    }
    
    public void togglePause() {
    	if(this.playing) {
    		mp.pause();
    	} else {
    		mp.start();
    	}
    	this.playing = !this.playing;
    }
    
    public void getPlaylist() {
    	try {
    		String xspf = this.httpGet("http://alpha.libre.fm/radio/xspf.php?sk=" + this.sessionKey + "&desktop=1.0");
    		this.playlist.parse(xspf);
    	} catch (Exception ex) {
    		Log.w("libredroid", "Unable to process playlist: " + ex.getMessage());
    		Toast.makeText(this, "Unable to process playlist: " + ex.getMessage(), Toast.LENGTH_LONG).show();
    	}
    }
    
    public boolean isPlaying() {
    	return this.playing;
    }
	
    public void tuneStation(String type, String station) {
    	Toast.makeText(this, "Tuning in...", Toast.LENGTH_LONG).show();
    	new TuneStationTask().execute(type, station);
    }

    
    private class TuneStationTask extends AsyncTask<String,String,String> {
	     
		protected String doInBackground(String... params) {
	    	 String type = params[0];
	    	 String station = params[1];
	    	 String result = "";
	    	 try {
	    		 result = LibreService.this.httpGet("http://alpha.libre.fm/radio/adjust.php?session=" + LibreService.this.sessionKey + "&url=librefm://" + type + "/" + station);
	    	 } catch (Exception ex) {
	    		 Log.w("libredroid", "Unable to tune station: " + ex.getMessage());
	    	 }
	    	 return result;
	     }

	     protected void onPostExecute(String output) {
	    	
	    	 if (output.length() == 0) {
	    		 return;
	    	 }
	    	 
	    	 LibreService.this.playlist = new Playlist();
	    	 
	    	 if (output.split(" ")[0].equals("FAILED")) {
	    		 Toast.makeText(LibreService.this, output.substring(7), Toast.LENGTH_LONG).show();
	    	 } else {
	    		 String[] result = output.split("[=\n]");
	    		 for (int x=0; x<result.length; x++)  {
	    			 if (result[x].trim().equals("stationname")) {
	    				 LibreService.this.stationName = result[x+1].trim();
	    			 }
	    		 }
	    		 LibreService.this.play();
	    	 }
	     }
	}
    
    public String getStationName() {
    	return LibreService.this.stationName;
    }
    
	
	public class LibreServiceBinder extends Binder implements ILibreService {

		public boolean login(String username, String password) {
			return LibreService.this.login(username, password);
		}
		
		public boolean isLoggedIn() {
			return LibreService.this.isLoggedIn();
		}
		
		public boolean isPlaying() {
			return LibreService.this.isPlaying();
		}
		
		public void stop() {
			LibreService.this.stop();
		}
		
		public void play() {
			LibreService.this.play();
		}
		
		public void next() {
			LibreService.this.next();
		}
		
		public void prev() {
			LibreService.this.prev();
		}
		
		public Song getSong() {
			return LibreService.this.getSong();
		}
		
		public Song getSong(int songNumber) {
			return LibreService.this.getSong(songNumber);
		}		
		
		public void tuneStation(String type, String station) {
			LibreService.this.tuneStation(type, station);
		}
		
		public void setCurrentPage(int page) {
			LibreService.this.currentPage = page;
		}
		
		public int getCurrentPage() {
			return LibreService.this.currentPage;
		}
		
		public void togglePause() {
			LibreService.this.togglePause();
		}
		
		public String getStationName() {
			return LibreService.this.getStationName();
		}
		
	}

}
