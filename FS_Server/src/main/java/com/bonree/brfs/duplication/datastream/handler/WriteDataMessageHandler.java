package com.bonree.brfs.duplication.datastream.handler;

import com.bonree.brfs.common.http.HandleResult;
import com.bonree.brfs.common.http.HandleResultCallback;
import com.bonree.brfs.common.http.HttpMessage;
import com.bonree.brfs.common.http.MessageHandler;
import com.bonree.brfs.common.utils.JsonUtils;
import com.bonree.brfs.common.utils.ProtoStuffUtils;
import com.bonree.brfs.duplication.datastream.DataHandleCallback;
import com.bonree.brfs.duplication.datastream.DataWriteResult;
import com.bonree.brfs.duplication.datastream.DuplicateWriter;
import com.bonree.brfs.duplication.datastream.ResultItem;

public class WriteDataMessageHandler implements MessageHandler {
	
	private DuplicateWriter duplicateWriter;
	
	public WriteDataMessageHandler(DuplicateWriter duplicateWriter) {
		this.duplicateWriter = duplicateWriter;
	}

	@Override
	public void handle(HttpMessage msg, HandleResultCallback callback) {
		WriteDataMessage writeMsg = ProtoStuffUtils.deserialize(msg.getContent(), WriteDataMessage.class);
		DataItem[] items = writeMsg.getItems();
		if(items == null || items.length == 0) {
			HandleResult result = new HandleResult();
			result.setSuccess(true);
			callback.completed(result);
			return;
		}
		
		duplicateWriter.write(writeMsg.getStorageNameId(), items, new DataWriteCallback(callback));
	}

	private class DataWriteCallback implements DataHandleCallback<DataWriteResult> {
		
		private HandleResultCallback callback;
		
		public DataWriteCallback(HandleResultCallback callback) {
			this.callback = callback;
		}

		@Override
		public void completed(DataWriteResult writeResult) {
			HandleResult result = new HandleResult();
			result.setSuccess(true);
			
			ResultItem[] resultItems = writeResult.getItems();
			result.setData(JsonUtils.toJsonBytes(resultItems));
			
			callback.completed(result);
		}

		@Override
		public void error(Throwable t) {
			HandleResult result = new HandleResult();
			result.setSuccess(false);
			result.setCause(t);
			
			callback.completed(result);
		}
		
	}
}
