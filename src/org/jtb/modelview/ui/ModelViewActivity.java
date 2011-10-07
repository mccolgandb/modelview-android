package org.jtb.modelview.ui;

import static org.jtb.modelview.d3.Vertex.X;
import static org.jtb.modelview.d3.Vertex.Y;

import org.jtb.modelview.R;
import org.jtb.modelview.assetbrowser.AbstractBrowseElement;
import org.jtb.modelview.assetbrowser.ExternalBrowseElement;
import org.jtb.modelview.d3.MeshRenderer;
import org.jtb.modelview.d3.ModelLoadException;
import org.jtb.modelview.d3.Vertex;
import org.jtb.modelview.touch.VersionedMotionEvent;
import org.jtb.modelview.util.Helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.FloatMath;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

public class ModelViewActivity extends Activity implements OnClickListener,
		View.OnTouchListener {
	private final String TAG = this.getClass().getName();

	private static final float MT_THRESHOLD = 10f;

	static final int RESULT_INIT = 0;
	static final int RESULT_NONE = 1;

	static final int LOADING_DIALOG = 0;
	static final int LOAD_ERROR_DIALOG = 1;

	private static final int TOUCHMODE_DRAG = 0;
	private static final int TOUCHMODE_ZOOM = 1;
	private static final int TOUCHMODE_NONE = -1;

	private ProgressDialog loadingDialog;
	private AlertDialog loadErrorDialog;

	static final int SHOW_LOADING_WHAT = 0;
	static final int SHOW_LOAD_ERROR_WHAT = 1;
	static final int HIDE_LOADING_WHAT = 2;
	static final int HIDE_LOAD_ERROR_WHAT = 3;
	static final int PREPARE_SURFACE_WHAT = 4;

	private GestureDetector gestureDetector = null;
	private Vertex lastRotate = new Vertex();
	private MeshRenderer renderer = null;
	private GLSurfaceView surfaceView = null;
	private ModelLoadException loadException = null;
	private AbstractBrowseElement browseElement = null;
	private OptionHandler optionHandler = null;
	private int touchMode = TOUCHMODE_DRAG;
	private float oldDist = 1f;

	TextView tvInfoPath;
	TextView tvInfoDate;

	private Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case SHOW_LOADING_WHAT:
				try {
					showDialog(LOADING_DIALOG);
				} catch (WindowManager.BadTokenException e) {
					Log.w(TAG, "^ couldn't show loading dialog", e);
				}
				break;
			case SHOW_LOAD_ERROR_WHAT:
				try {
					showDialog(LOAD_ERROR_DIALOG);
				} catch (WindowManager.BadTokenException e) {
					Log.w(TAG, "^ Couldn't show error dialog", e);
				}
				break;
			case HIDE_LOADING_WHAT:
				if (loadingDialog.isShowing()) {
					loadingDialog.hide();
				}
				break;
			case HIDE_LOAD_ERROR_WHAT:
				if (loadErrorDialog.isShowing()) {
					loadErrorDialog.hide();
				}
				break;
			case PREPARE_SURFACE_WHAT:
				prepareSurface();
				break;
			}
		}
	};

	private static class ModelViewGestureDetector extends
			SimpleOnGestureListener {
		private ModelViewActivity activity;

		ModelViewGestureDetector(ModelViewActivity activity) {
			this.activity = activity;
		}

		@Override
		public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
				float velocityY) {
			activity.surfaceView
					.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

			activity.renderer.mesh.dySpeed = velocityX / 1000;
			activity.renderer.mesh.dxSpeed = velocityY / 1000;

			return super.onFling(e1, e2, velocityX, velocityY);
		}
	}

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// full screen
		this.requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN);

		// This must be after any requestWindowFeature
		Intent intent = getIntent();

		String action = intent.getAction();
		Uri data = intent.getData();

		Log.d(TAG, "^ Launch action: " + action);

		if ((action != null) && (data != null)) {
			if (action.equals(Intent.ACTION_VIEW)) {
				browseElement = new ExternalBrowseElement(this, data.toString()
						.replace("file:/", ""));
			}
		} else {
			browseElement = (savedInstanceState != null) ? (AbstractBrowseElement) savedInstanceState
					.get("browseElement") : null;
		}
		if (browseElement == null) {
			Bundle extras = getIntent().getExtras();
			browseElement = (extras != null) ? (AbstractBrowseElement) extras
					.get("browseElement") : null;
		}
		if (browseElement == null) {
			Log.e(TAG, "^ browseElement is null!");
			return; // error
		}
		browseElement.setContext(this);

		// Gesture detection
		gestureDetector = new GestureDetector(
				new ModelViewGestureDetector(this));
		optionHandler = new OptionHandler(this);

		init();
	}

	private void init() {
		Log.d(TAG, "^ INIT - loading: " + browseElement.getFullPath());

		showDialog(LOADING_DIALOG);
		new Thread(new Runnable() {
			public void run() {
				try {
					renderer = new MeshRenderer(ModelViewActivity.this,
							browseElement);
					handler.sendEmptyMessage(PREPARE_SURFACE_WHAT);
				} catch (ModelLoadException e) {
					setLoadException(e);
					handler.sendEmptyMessage(SHOW_LOAD_ERROR_WHAT);
				} finally {
					handler.sendEmptyMessage(HIDE_LOADING_WHAT);
				}

			}
		}).start();
	}

	private void setLoadException(ModelLoadException e) {
		loadException = e;
	}

	public void setRenderMode(int mode) {
		surfaceView.setRenderMode(mode);
	}

	private void prepareSurface() {
		setContentView(R.layout.model_activity);
		surfaceView = (GLSurfaceView) findViewById(R.id.modelSurfaceView);

		tvInfoPath = (TextView) findViewById(R.id.modelInfoPath);
		tvInfoDate = (TextView) findViewById(R.id.modelInfoDate);

		tvInfoPath.setText(browseElement.getFullPath());
		long time = browseElement.getLastModoficationDate();
		if (time == 0) {
			tvInfoDate.setVisibility(View.GONE);
		} else {
			tvInfoDate.setText(Helper.createDatestring(time));
		}

		surfaceView.setRenderer(renderer);
		surfaceView.setSoundEffectsEnabled(false);
		surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
		surfaceView.setOnClickListener(this);
		surfaceView.setOnTouchListener(this);

		surfaceView.requestRender();
	}

	@Override
	public void onResume() {
		super.onResume();
		if (surfaceView != null) {
			surfaceView.requestRender();
		}
	}

	@Override
	public void onPause() {
		super.onPause();
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
			finish();
			return true;
		}

		return super.onKeyDown(keyCode, event);
	}

	public boolean onTouch(View view, MotionEvent rawEvent) {
		VersionedMotionEvent event = VersionedMotionEvent.newInstance(rawEvent);

		if (gestureDetector.onTouchEvent(rawEvent)) {
			return true;
		}

		switch (event.getAction() & MotionEvent.ACTION_MASK) {
		case MotionEvent.ACTION_DOWN:
			surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

			renderer.mesh.dxSpeed = 0.0f;
			renderer.mesh.dySpeed = 0.0f;

			lastRotate.vertex[X] = event.getX();
			lastRotate.vertex[Y] = event.getY();

			touchMode = TOUCHMODE_DRAG;
			break;
		case MotionEvent.ACTION_POINTER_DOWN:
			if (Build.VERSION.SDK_INT > 4) {
				oldDist = spacing(event);
				if (oldDist > MT_THRESHOLD) {
					touchMode = TOUCHMODE_ZOOM;
				}
			}
			break;
		case MotionEvent.ACTION_UP:
		case MotionEvent.ACTION_POINTER_UP:
			touchMode = TOUCHMODE_NONE;
			break;
		case MotionEvent.ACTION_MOVE:
			surfaceView.requestRender();
			if (touchMode == TOUCHMODE_DRAG) {
				renderer.mesh.ry += event.getX() - lastRotate.vertex[X];
				renderer.mesh.rx += event.getY() - lastRotate.vertex[Y];
				lastRotate.vertex[X] = event.getX();
				lastRotate.vertex[Y] = event.getY();
			} else if (touchMode == TOUCHMODE_ZOOM) {
				float newDist = spacing(event);
				if (newDist > MT_THRESHOLD) {
					renderer.mesh.scale *= newDist / oldDist;
				}
				oldDist = newDist;
			}

			break;
		}

		return super.onTouchEvent(rawEvent);
	}

	private float spacing(VersionedMotionEvent event) {
		float x = event.getX(0) - event.getX(1);
		float y = event.getY(0) - event.getY(1);
		return FloatMath.sqrt(x * x + y * y);
	}

	public void onClick(View v) {
		// TODO Auto-generated method stub
	}

	protected Dialog onCreateDialog(int id) {
		switch (id) {
		case LOADING_DIALOG:
			if (loadingDialog == null) {
				loadingDialog = new ProgressDialog(this);
				loadingDialog.setMessage("Loading ...");
				loadingDialog.setIndeterminate(true);
				loadingDialog
						.setOnCancelListener(new DialogInterface.OnCancelListener() {
							public void onCancel(DialogInterface dialog) {
								finish();
							}
						});
			}
			return loadingDialog;
		case LOAD_ERROR_DIALOG:
			if (loadErrorDialog == null) {
				AlertDialog.Builder builder = new AlertDialog.Builder(this);
				builder.setTitle("Error Loading");
				builder.setIcon(android.R.drawable.ic_dialog_alert);
				builder.setMessage(loadException.getMessage());
				builder.setNeutralButton("Back",
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int which) {
								dismissDialog(LOAD_ERROR_DIALOG);
								finish();
							}
						});

				loadErrorDialog = builder.create();
			}
			Log.e(TAG, "^ load error:", loadException);
			return loadErrorDialog;
		}
		return null;
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		// use pre-8 method of ensuring that our process is killed
		if (Build.VERSION.SDK_INT < 8) {
			android.os.Process.killProcess(android.os.Process.myPid());
		}
	}

	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.modelview_menu, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		return optionHandler.handle(item.getItemId());
	}

	protected void onActivityResult(int requestCode, int resultCode, Intent i) {
		if (requestCode == OptionHandler.PREFERENCES_REQUEST) {
			switch (resultCode) {
			case RESULT_INIT:
				init();
				break;
			default:
				break;
			}
		}
	}
}
