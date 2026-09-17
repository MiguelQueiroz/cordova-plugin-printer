const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function loadPrinter() {
    const calls = [];
    const context = {
        exports: {},
        navigator: { userAgent: 'iPhone' },
        require: () => (...args) => calls.push(args)
    };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../www/printer.js'), 'utf8'), context);
    return { printer: context.exports, calls };
}

test('PDF data URL and requested filename reach native printing unchanged', () => {
    const { printer, calls } = loadPrinter();
    const pdf = 'data:application/pdf;base64,JVBERi0xLjcK';
    let completed;
    printer.print(pdf, { name: 'appointment.pdf' }, value => { completed = value; });
    assert.equal(calls[0][2], 'Printer');
    assert.equal(calls[0][3], 'print');
    assert.equal(calls[0][4][0], pdf);
    assert.equal(calls[0][4][1].name, 'appointment.pdf');
    calls[0][0](true);
    assert.equal(completed, true);
});

test('legacy settings retain their meaning with upstream native code', () => {
    const { printer, calls } = loadPrinter();
    printer.print('<b>Receipt</b>', {
        landscape: true, graystyle: true, duplex: false,
        printerId: 'ipp://printer', bounds: [10, 20, 30, 40]
    });
    const options = calls[0][4][1];
    assert.equal(options.orientation, 'landscape');
    assert.equal(options.monochrome, true);
    assert.equal(options.duplex, 'none');
    assert.equal(options.printer, 'ipp://printer');
    assert.deepEqual(JSON.parse(JSON.stringify(options.ui)), { left: 10, top: 20, width: 30, height: 40 });
});

test('upstream options take precedence over legacy aliases', () => {
    const { printer, calls } = loadPrinter();
    printer.print('Receipt', { landscape: true, orientation: 'portrait', graystyle: true, monochrome: false });
    assert.equal(calls[0][4][1].orientation, 'portrait');
    assert.equal(calls[0][4][1].monochrome, false);
});

test('legacy availability callback retains its scope', () => {
    const { printer, calls } = loadPrinter();
    const scope = {};
    printer.isAvailable(function (available) { this.available = available; }, scope);
    assert.equal(calls[0][3], 'check');
    assert.equal(calls[0][4][0], null);
    calls[0][0](true);
    assert.equal(scope.available, true);
});
