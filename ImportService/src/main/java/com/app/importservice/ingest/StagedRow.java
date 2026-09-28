package com.app.importservice.ingest;

/**
 * 1 dòng CSV sẵn sàng ghi vào import_row.
 *
 * @param rowNo  số thứ tự bản ghi dữ liệu trong file (bắt đầu từ 1, không tính dòng header)
 * @param values đúng {@link CustomerCsv#COLUMNS} giá trị, đã cắt khoảng trắng; ô trống = null
 * @param error  null nếu hợp lệ; ngược lại là lý do (dòng này ghi FAILED, không gọi API)
 */
public record StagedRow(int rowNo, String[] values, String error) {

    public boolean valid() {
        return error == null;
    }
}
